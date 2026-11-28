#!/usr/bin/env bash
# Run by the isolated stage deployment job through SSM Run Command.
set -euo pipefail
umask 077
: "${RELEASE:?}" "${STAGE_IP:?}" "${TEST_CIDR:?}" "${STAGE_CODE_HASH:?}"
cd /home/ubuntu/rougether-stage
exec 9>/run/rougether-deploy.lock
flock -n 9 || { echo '다른 배포가 진행 중입니다.' >&2; exit 1; }
base=(docker compose -p rougether-rehearsal -f compose.yaml)
service=$("${base[@]}" ps -q user-api)
test -n "$service"
test "$(docker network inspect -f '{{.Internal}}' rougether-rehearsal_default)" = true
docker inspect "$service" | python3 -c '
import json, sys
c=json.load(sys.stdin)[0]
env=dict(s.split("=",1) for s in c["Config"]["Env"])
assert set(c["NetworkSettings"]["Networks"]) == {"rougether-rehearsal_default"}
assert env["ROUGETHER_ENVIRONMENT"] == "rehearsal"
assert env["DB_URL"].split("?")[0] == "jdbc:mysql://mysql:3306/rougether_stage"
'
test -z "$("${base[@]}" ps -q --status running batch)"
# Fresh synthetic schemas have no catalog goals, which the house creation API requires.
db=$("${base[@]}" ps -q mysql)
test -n "$db"
test "$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Name}}{{end}}{{end}}' "$db")" = rougether-rehearsal_stage-db
"${base[@]}" exec -T mysql sh -ec 'test "$MYSQL_DATABASE" = rougether_stage; test "$MYSQL_USER" = stage; export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysql -u "$MYSQL_USER" "$MYSQL_DATABASE"' <<'SQL'
INSERT INTO goals (code, name, sort_order, is_active)
SELECT 'STAGE_JOURNEY', 'Rehearsal', 9999, TRUE
WHERE NOT EXISTS (SELECT 1 FROM goals WHERE is_active = TRUE);
SQL
install -d -m 700 .stage-https/releases
stage_root=$(realpath .stage-https)
old=$(readlink -f "$stage_root/current" || true)
export STAGE_HTTPS_DIR
STAGE_HTTPS_DIR=$(mktemp -d "$stage_root/releases/$RELEASE.XXXXXX")
tar -xzf "$1" -C "$STAGE_HTTPS_DIR"
# Compose reads this env file literally (bcrypt contains dollar signs).
printf "STAGE_IP=%s\nTEST_CIDR=%s\nSTAGE_CODE_HASH='%s'\n" "$STAGE_IP" "$TEST_CIDR" "$STAGE_CODE_HASH" > "$STAGE_HTTPS_DIR/gateway.env"
# These credentials are valid only inside the synthetic object store.
object_secret=$(openssl rand -hex 32)
printf 'AWS_ACCESS_KEY_ID=stage-photos
AWS_SECRET_ACCESS_KEY=%s
' "$object_secret" > "$STAGE_HTTPS_DIR/qa-object.env"
printf '{"identities":[{"name":"stage-api","credentials":[{"accessKey":"stage-photos","secretKey":"%s"}],"actions":["Read:rougether-synthetic-only","Write:rougether-synthetic-only"]}]}' "$object_secret" > "$STAGE_HTTPS_DIR/qa-s3.json"
unset object_secret
compose=("${base[@]}" -f "$STAGE_HTTPS_DIR/compose.https.yaml")
"${compose[@]}" config --quiet
"${compose[@]}" pull stage-https stage-google-keys stage-objects
rollback() {
  "${compose[@]}" stop stage-objects
  if test -f "$old/compose.https.yaml"; then
    previous=(stage-google-keys user-api stage-https)
    if grep -q '^  stage-objects:' "$old/compose.https.yaml"; then previous+=(stage-objects); fi
    STAGE_HTTPS_DIR="$old" "${base[@]}" -f "$old/compose.https.yaml" up -d --no-deps "${previous[@]}"
  else
    "${compose[@]}" stop stage-https stage-google-keys
    "${base[@]}" up -d --no-deps user-api
  fi
}
trap 'rollback' ERR
"${compose[@]}" up -d --no-deps stage-objects stage-google-keys user-api stage-https
gateway=$("${compose[@]}" ps -q stage-https)
pid=$(docker inspect -f '{{.State.Pid}}' "$gateway")
probe=(nsenter -t "$pid" -n curl -sS --max-time 6 --noproxy '*' --connect-to "$STAGE_IP:443:127.0.0.1:443")
ready=false
for attempt in {1..60}; do
  if "${probe[@]}" -f "https://$STAGE_IP/api/v1/health" 2>/dev/null | python3 -c 'import json,sys; assert json.load(sys.stdin)["status"]=="ok"' 2>/dev/null; then
    ready=true; break
  fi
  sleep 5
done
"$ready"
for path in /api/v1/auth/dev-login /admin/health /actuator/health; do
  status=$("${probe[@]}" -o /dev/null -w '%{http_code}' "https://$STAGE_IP$path")
  test "$status" = 404
done
test "$("${probe[@]}" -o /dev/null -w '%{http_code}' "https://$STAGE_IP/api/v1/me")" = 401
# A browser sends Origin on writes. Check Spring accepts this exact HTTPS origin.
google_probe=("${probe[@]}" -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' --data '{"idToken":"invalid"}')
test "$("${google_probe[@]}" -H "Origin: https://$STAGE_IP" "https://$STAGE_IP/api/v1/auth/google")" = 401
test "$("${google_probe[@]}" -H 'Origin: https://other.invalid' "https://$STAGE_IP/api/v1/auth/google")" = 403
ln -s "$STAGE_HTTPS_DIR" "$STAGE_HTTPS_DIR.next"
mv -Tf "$STAGE_HTTPS_DIR.next" "$stage_root/current"
echo 'Trusted IP TLS, synthetic API health and private route restrictions verified.'
