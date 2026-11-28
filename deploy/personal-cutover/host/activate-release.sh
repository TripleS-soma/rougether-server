#!/usr/bin/env bash
# 대상에서 첫 쓰기 가능 시점과 binlog 좌표를 남긴 후 기존 앱 주소와 새 주소를 함께 연다.
set -Eeuo pipefail
umask 077
exec 9>/run/rougether-deploy.lock
flock -w 45 9
base=/var/lib/rougether-personal-cutover
state="$base/serving"
token=$(curl --noproxy '*' -fsS --max-time 3 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token)
test "$(curl --noproxy '*' -fsS --max-time 3 -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/instance-id)" = EXPECTED_MACHINE
[[ "$SSM_Snapshot" =~ ^[0-9]{8}-[0-9]{4}$ && "$SSM_Digest" =~ ^[a-f0-9]{64}$ && "$SSM_ConfigDigest" =~ ^[a-f0-9]{64}$ && "$SSM_Bundle" =~ ^[a-f0-9]{64}$ ]]
slot="$SSM_Snapshot-${SSM_Digest:0:12}"
tools="$base/release-tools/$SSM_Bundle"
ref="$SSM_Snapshot $SSM_Digest $SSM_ConfigDigest $SSM_Bundle"
test "$(cat "$state/selection")" = "$ref"
test "$(cat "$state/warmed")" = "$ref"
test ! -e "$state/sealing" && test ! -e "$state/sealed"
test "$(realpath "$tools")" = "$tools"
cd "$tools"
test "$(find gateway runtime routing -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)" = "$SSM_Bundle"
python3 -B runtime/release_candidate.py "$SSM_Snapshot" "$SSM_Digest" "$SSM_ConfigDigest" >/dev/null
export RUNTIME_CONFIG="$base/release-config/$SSM_ConfigDigest" RUNTIME_JARS=/home/ubuntu/rougether-stage RELEASE_ROUTING="$tools/routing"
export DB_RELEASE_DATA="$base/release-$slot/copy/data" DB_RELEASE_CONFIG="$RUNTIME_CONFIG/database" DB_ROOT_CNF="$base/$slot/client/client.cnf"
export DB_COLLATION=utf8mb4_0900_ai_ci RELEASE_FLAGS="$state/user-settings.env" CUTOVER_MODE=sealed_api
printf '%s' "$SSM_SourceSettings" | python3 -B runtime/source_settings.py "$RELEASE_FLAGS" >/dev/null
compose=(docker compose -p rougether-release -f runtime/compose.apps.yaml -f runtime/compose.identity.yaml -f runtime/compose.database.yaml -f runtime/compose.routing.yaml -f runtime/compose.live.yaml)
"${compose[@]}" --profile writer config --quiet
sql() { "${compose[@]}" exec -T mysql mysql --defaults-extra-file=/run/release-root.cnf -N -B rougether; }
verify_origins() {
  python3 - "$1" <<'PY'
import sys,uuid,urllib.request,urllib.error
for host in ("43.203.93.12","dkfiwkal2ezg9.cloudfront.net"):
    url="https://"+host+"/api/v1/health?release="+uuid.uuid4().hex
    with urllib.request.urlopen(urllib.request.Request(url,headers={"Cache-Control":"no-cache"}),timeout=8) as r:
        if r.status!=200 or r.headers.get("X-Rougether-Origin")!="personal-api":raise RuntimeError("Origin mismatch")
    if host!="43.203.93.12":continue
    path="https://"+host+"/api/v1/admin?release="+uuid.uuid4().hex
    try:
        reply=urllib.request.urlopen(path,timeout=8)
    except urllib.error.HTTPError as e:
        reply=e
    with reply:
        if reply.status!=(503 if sys.argv[1]=="warm" else 404):raise RuntimeError("Unexpected gateway mode")
PY
}
probe() {
  local id pid
  id=$("${compose[@]}" ps -q "$1")
  [[ "$id" =~ ^[0-9a-f]{64}$ ]] || return 1
  pid=$(docker inspect -f '{{.State.Pid}}' "$id")
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] && nsenter -t "$pid" -n -- curl --noproxy '*' -s --max-time 4 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$2$3"
}
if [[ -f "$state/accepted-writes" ]]; then
  test "$(cat "$state/accepted-writes")" = "$ref"
  verify_origins live
  test "$(probe admin-api 8081 /admin/health)" = 200
  test "$(probe batch 8082 /actuator/health)" = 200
else
  # A partially applied activation may have accepted writes. Seal and reconcile that case.
  test ! -e "$state/activating"
  verify_origins warm
  test "$(echo "SELECT @@super_read_only,OPTIONS FROM information_schema.schemata_extensions WHERE schema_name=DATABASE()" | sql)" = $'1\tREAD ONLY=1'
  echo 'SHOW BINARY LOG STATUS' | sql > "$state/before-writes.pending"
  test -s "$state/before-writes.pending"
  mv "$state/before-writes.pending" "$state/before-writes"
  printf '%s\n' "$ref" > "$state/activating.pending"
  mv "$state/activating.pending" "$state/activating"
  fail_closed() {
    local rc=$?
    trap - EXIT
    if [[ "$rc" != 0 ]]; then
      # Remove all ingress and producers before restoring persistent DB protection.
      local svc
      for svc in gateway db-proxy batch user-api admin-api; do "${compose[@]}" --profile writer stop --timeout 45 "$svc" >/dev/null 2>&1 || true; done
      if ! echo 'SET SESSION lock_wait_timeout=7; SET GLOBAL super_read_only=OFF; SET GLOBAL read_only=OFF; ALTER DATABASE rougether READ ONLY=1; SET PERSIST read_only=ON; SET PERSIST super_read_only=ON;' | sql >/dev/null 2>&1; then "${compose[@]}" stop --timeout 90 mysql >/dev/null 2>&1 || true; fi
      echo '개인 AWS 활성화 중단. 대상 쓰기 가능 시점과 binlog를 보존했습니다. 원본 복귀 전에 대상 동결을 확인하세요.' >&2
    fi
    exit "$rc"
  }
  trap fail_closed EXIT
  CUTOVER_MODE=maintenance "${compose[@]}" up -d --no-deps gateway >/dev/null
  "${compose[@]}" stop --timeout 45 user-api admin-api >/dev/null
  echo 'SET PERSIST super_read_only=OFF; SET PERSIST read_only=OFF; SET SESSION lock_wait_timeout=7; ALTER DATABASE rougether READ ONLY=0;' | sql >/dev/null
  test "$(echo "SELECT @@global.read_only,@@global.super_read_only,LOCATE('READ ONLY=1',COALESCE(OPTIONS,'')) FROM information_schema.schemata_extensions WHERE schema_name=DATABASE()" | sql)" = $'0\t0\t0'
  "${compose[@]}" --profile writer up -d --no-deps user-api admin-api batch >/dev/null
  for i in {1..90}; do
    if [[ "$(probe user-api 8080 /api/v1/health)" = 200 && "$(probe admin-api 8081 /admin/health)" = 200 && "$(probe batch 8082 /actuator/health)" = 200 ]]; then break; fi
    sleep 2
  done
  test "$(probe user-api 8080 /api/v1/health)" = 200
  test "$(probe admin-api 8081 /admin/health)" = 200
  test "$(probe batch 8082 /actuator/health)" = 200
  CUTOVER_MODE=live_api "${compose[@]}" up -d --no-deps gateway >/dev/null
  for i in {1..15}; do if verify_origins live 2>/dev/null; then break; fi; sleep 2; done
  verify_origins live
  printf '%s\n' "$ref" > "$state/accepted.pending"
  mv "$state/accepted.pending" "$state/accepted-writes"
  trap - EXIT
fi
printf '{"active":true,"snapshot":"%s","originVerified":true,"adminHealth":200,"batchHealth":200,"binlogBaseline":true}\n' "$SSM_Snapshot"
