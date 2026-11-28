#!/usr/bin/env bash
# 승인된 최종 복제본만 읽기 전용으로 열고 두 API의 health를 확인한다.
set -Eeuo pipefail
umask 077
exec 9>/run/rougether-deploy.lock
flock -w 45 9
base=/var/lib/rougether-personal-cutover
token=$(curl --noproxy '*' -fsS --max-time 3 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token)
metadata() { curl --noproxy '*' -fsS --max-time 3 -H "X-aws-ec2-metadata-token: $token" "http://169.254.169.254/latest/meta-data/$1"; }
test "$(metadata instance-id)" = EXPECTED_MACHINE
private=$(metadata local-ipv4)
[[ "$SSM_Snapshot" =~ ^[0-9]{8}-[0-9]{4}$ && "$SSM_Digest" =~ ^[a-f0-9]{64}$ && "$SSM_ConfigDigest" =~ ^[a-f0-9]{64}$ && "$SSM_Bundle" =~ ^[a-f0-9]{64}$ ]]
slot="$SSM_Snapshot-${SSM_Digest:0:12}"
original="$base/$slot"
release="$base/release-$slot"
tools="$base/release-tools/$SSM_Bundle"
test "$(realpath "$tools")" = "$tools"
cd "$tools"
actual=$(find gateway runtime routing -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
test "$actual" = "$SSM_Bundle"
python3 -B runtime/release_candidate.py "$SSM_Snapshot" "$SSM_Digest" "$SSM_ConfigDigest" >/dev/null
source_held() {
  python3 - <<'PY'
import urllib.request,urllib.error,uuid
req=urllib.request.Request('https://dkfiwkal2ezg9.cloudfront.net/api/v1/health?cutover='+uuid.uuid4().hex,headers={'Cache-Control':'no-cache'})
try:
    with urllib.request.urlopen(req,timeout=6):
        raise RuntimeError('Source still serving')
except urllib.error.HTTPError as err:
    with err:
        if err.code!=503 or err.headers.get('X-Rougether-Cutover')!='source-held':
            raise RuntimeError('Source maintenance marker missing')
PY
}
source_held
export RUNTIME_CONFIG="$base/release-config/$SSM_ConfigDigest" RUNTIME_JARS=/home/ubuntu/rougether-stage RELEASE_ROUTING="$tools/routing"
export DB_RELEASE_DATA="$release/copy/data" DB_RELEASE_CONFIG="$RUNTIME_CONFIG/database" DB_ROOT_CNF="$original/client/client.cnf"
export DB_COLLATION=utf8mb4_0900_ai_ci CUTOVER_MODE=sealed_api
openssl verify -CAfile "$DB_RELEASE_CONFIG/ca.pem" -verify_ip "$private" "$DB_RELEASE_CONFIG/server.pem" >/dev/null
test -s "$DB_ROOT_CNF"
common=(docker compose -p rougether-release -f runtime/compose.apps.yaml -f runtime/compose.identity.yaml -f runtime/compose.database.yaml -f runtime/compose.routing.yaml)
warm=("${common[@]}" -f runtime/compose.standby.yaml -f runtime/compose.warm.yaml)
"${warm[@]}" config --quiet
state="$base/serving"
install -d -m 0700 "$state"
ref="$SSM_Snapshot $SSM_Digest $SSM_ConfigDigest $SSM_Bundle"
if [[ -e "$state/selection" ]]; then
  test "$(cat "$state/selection")" = "$ref"
else
  test -z "$(docker ps -aq --filter label=com.docker.compose.project=rougether-release)"
  printf '%s\n' "$ref" > "$state/selection.pending"
  mv "$state/selection.pending" "$state/selection"
fi
test ! -e "$state/accepted-writes"
test ! -e "$state/activating"
test ! -e "$state/sealing"
test ! -e "$state/sealed"
# A previously selected release may own this data after a retry.
for id in $(docker ps -aq); do
  docker inspect "$id" | python3 -c 'import sys,json; c=json.load(sys.stdin)[0]; m=[x for x in c["Mounts"] if x.get("Source")==sys.argv[1]]; assert not m or (c["Config"]["Labels"].get("com.docker.compose.project")=="rougether-release" and c["Config"]["Labels"].get("com.docker.compose.service")=="mysql" and m[0]["Destination"]=="/var/lib/mysql")' "$DB_RELEASE_DATA"
done
proxy=rougether-furniture-private-proxy
test "$(docker inspect -f '{{.HostConfig.NetworkMode}}' "$proxy")" = host
docker inspect "$proxy" | python3 -c 'import sys,json; mounts=json.load(sys.stdin)[0]["Mounts"]; assert [(m["Source"],m["Destination"]) for m in mounts]==[(sys.argv[1],"/etc/haproxy.cfg")]' "$base/furniture-fixture/haproxy.cfg"
# Only the prior synthetic stack is put away. Its volumes and public certificate survive.
if [[ ! -e "$state/previous" ]]; then
  docker ps -q --no-trunc --filter label=com.docker.compose.project=rougether-rehearsal > "$state/previous.pending"
  test -s "$state/previous.pending"
  mv "$state/previous.pending" "$state/previous"
fi
while IFS= read -r id; do
  test "$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$id")" = rougether-rehearsal
  if [[ "$(docker inspect -f '{{.State.Running}}' "$id")" = true ]]; then docker stop --time 45 "$id" >/dev/null; fi
done < "$state/previous"
if [[ "$(docker inspect -f '{{.State.Running}}' "$proxy")" = true ]]; then docker stop --time 20 "$proxy" >/dev/null; fi
# Public firewall policy is controlled by the operator after the sealed endpoint passes.
"${warm[@]}" up -d --no-deps mysql db-proxy >/dev/null
sql() { "${warm[@]}" exec -T mysql mysql --defaults-extra-file=/run/release-root.cnf -N -B rougether; }
for i in {1..75}; do
  if [[ "$(echo 'SELECT @@global.super_read_only' | sql 2>/dev/null)" = 1 ]]; then break; fi
  sleep 2
done
test "$(echo "SELECT @@global.super_read_only,@@require_secure_transport,@@event_scheduler,@@collation_database,OPTIONS FROM information_schema.schemata_extensions WHERE schema_name=DATABASE()" | sql)" = $'1\t1\tDISABLED\tutf8mb4_0900_ai_ci\tREAD ONLY=1'
"${warm[@]}" up -d --no-deps user-api admin-api >/dev/null
for svc in user-api admin-api; do
  id=$("${warm[@]}" ps -q "$svc")
  bash runtime/warm_network.sh "$id" "$svc" "$private"
done
probe() {
  local svc=$1 port=$2 path=$3 id pid
  id=$("${warm[@]}" ps -q "$svc")
  pid=$(docker inspect -f '{{.State.Pid}}' "$id")
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] && nsenter -t "$pid" -n -- curl --noproxy '*' --silent --max-time 4 --output /dev/null --write-out '%{http_code}' "http://127.0.0.1:$port$path"
}
for i in {1..90}; do
  if [[ "$(probe user-api 8080 /api/v1/health)" = 200 && "$(probe admin-api 8081 /admin/health)" = 200 ]]; then break; fi
  sleep 2
done
test "$(probe user-api 8080 /api/v1/health)" = 200
test "$(probe admin-api 8081 /admin/health)" = 200
source_held
"${warm[@]}" up -d --no-deps gateway >/dev/null
curlargs=(--noproxy '*' --silent --show-error --max-time 7 --connect-to 43.203.93.12:443:127.0.0.1:443)
for i in {1..15}; do
  if [[ "$(curl "${curlargs[@]}" -o /dev/null -w '%{http_code}' https://43.203.93.12/api/v1/health 2>/dev/null)" = 200 ]]; then break; fi
  sleep 2
done
curl "${curlargs[@]}" --fail -D - -o /dev/null https://43.203.93.12/api/v1/health | tr -d '\r' | grep -ix 'X-Rougether-Origin: personal-api' >/dev/null
test "$(curl "${curlargs[@]}" -X POST -o /dev/null -w '%{http_code}' https://43.203.93.12/api/v1/health)" = 503
test -z "$(docker ps -q --filter label=com.docker.compose.project=rougether-release --filter label=com.docker.compose.service=batch)"
printf '%s\n' "$ref" > "$state/warmed.pending"
mv "$state/warmed.pending" "$state/warmed"
printf '{"warmed":true,"publicWrites":false,"databaseReadOnly":true,"userHealth":200,"adminHealth":200,"batchRunning":false,"snapshot":"%s","digest":"%s","config":"%s","bundle":"%s"}\n' "$SSM_Snapshot" "$SSM_Digest" "$SSM_ConfigDigest" "$SSM_Bundle"
