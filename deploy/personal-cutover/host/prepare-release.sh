#!/usr/bin/env bash
# 개인 호스트에 배포 후보 파일을 준비하고 기존 리스너, 경로와 TLS를 읽기 전용으로 확인한다.
set -Eeuo pipefail
umask 077
exec 9>/run/rougether-deploy.lock
flock -w 45 9
base=/var/lib/rougether-personal-cutover
fixture="$base/furniture-fixture"
token=$(curl --noproxy '*' -fsS --max-time 3 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token)
metadata() { curl --noproxy '*' -fsS --max-time 3 -H "X-aws-ec2-metadata-token: $token" "http://169.254.169.254/latest/meta-data/$1"; }
test "$(metadata instance-id)" = EXPECTED_MACHINE
private=$(metadata local-ipv4)
test "$private" = "$(cat "$fixture/private.addr")"
[[ "$SSM_ConfigDigest" =~ ^[a-f0-9]{64}$ ]]
export RUNTIME_CONFIG="$base/release-config/$SSM_ConfigDigest"
export DB_RELEASE_CONFIG="$RUNTIME_CONFIG/database"
export RUNTIME_JARS=/home/ubuntu/rougether-stage
test -s "$RUNTIME_CONFIG/user-api/JWT_SECRET"
for key in OPERATIONS_WEBEX_BOT_TOKEN OPERATIONS_WEBEX_ROOM_ID; do
  test -s "$RUNTIME_CONFIG/user-api/$key"
  cmp -s "$RUNTIME_CONFIG/user-api/$key" "$RUNTIME_CONFIG/batch/$key"
  test ! -e "$RUNTIME_CONFIG/admin-api/$key"
done
openssl x509 -in "$DB_RELEASE_CONFIG/server.pem" -checkend 86400 -noout >/dev/null
openssl verify -CAfile "$DB_RELEASE_CONFIG/ca.pem" -verify_ip "$private" "$DB_RELEASE_CONFIG/server.pem" >/dev/null
test -z "$(find "$RUNTIME_CONFIG" -type l -print -quit)"
test -z "$(find "$RUNTIME_CONFIG" -type f -perm /077 -print -quit)"
cd "$RUNTIME_JARS"
sha256sum --check --status <<'JARS'
8ae9387d6b4c8bb0741a058170c43c15518db6c00116c03fc77d4dfb91f094a2  user-api.jar
2d0f2b7b33cd00ad7201ae2a7504f9c94c363374e915a27f900bbeeb0a92677b  admin-api.jar
d0cddb42c2d814425c3830358f1014e5548d89054fc25fcfc139307856313024  batch.jar
JARS
test "$(docker volume inspect -f '{{.Name}}' rougether-rehearsal_stage-tls)" = rougether-rehearsal_stage-tls
test -x "$base/runtime-identity/aws_signing_helper"
for identity in user admin; do test -s "$base/runtime-identity/$identity/config"; done
root="$base/release-tools"
install -d -m 0700 "$root"
work=$(mktemp -d "$root/.prepare.XXXXXXXX")
trap 'python3 -c "from pathlib import Path; import shutil,sys; p=Path(sys.argv[1]); assert p.parent==Path(sys.argv[2]) and p.name.startswith(\".prepare.\"); shutil.rmtree(p,ignore_errors=True)" "$work" "$root"' EXIT
cd "$work"
mkdir runtime gateway
# RELEASE_TOOLS
read -r -a interfaces < "$fixture/allow.addr" || test "${#interfaces[@]}" -gt 0
python3 runtime/render_routing.py routing "$private" "${interfaces[@]}"
export RELEASE_ROUTING="$work/routing" DB_RELEASE_DATA="$work/unselected" DB_COLLATION=utf8mb4_0900_ai_ci CUTOVER_MODE=sealed_api
compose=(docker compose --profile writer -p rougether-release -f runtime/compose.apps.yaml -f runtime/compose.identity.yaml -f runtime/compose.database.yaml -f runtime/compose.routing.yaml)
"${compose[@]}" config --format json | python3 runtime/release_plan.py
DB_ROOT_CNF="$work/unselected-root.cnf" "${compose[@]}" -f runtime/compose.standby.yaml -f runtime/compose.warm.yaml config --quiet
# Leave only immutable files. All mutable candidate settings come from the protected invocation.
bundle=$(find gateway runtime routing -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
[[ "$bundle" =~ ^[a-f0-9]{64}$ ]]
if [[ -d "$root/$bundle" ]]; then
  for dir in gateway runtime routing; do diff -qr "$dir" "$root/$bundle/$dir" >/dev/null; done
else
  mv -T "$work" "$root/$bundle"
fi
printf '{"prepared":true,"bundle":"%s","config":"%s","services":6,"productionWritersStarted":0}\n' "$bundle" "$SSM_ConfigDigest"
