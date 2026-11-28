#!/usr/bin/env bash
# 서비스용 데이터와 binlog를 보존한다. 원본 계정의 쓰기는 별도로 재개한다.
set -Eeuo pipefail
umask 077
exec 9>/run/rougether-deploy.lock
flock -w 45 9
base=/var/lib/rougether-personal-cutover
state="$base/serving"
token=$(curl --noproxy '*' -fsS --max-time 3 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token)
test "$(curl --noproxy '*' -fsS --max-time 3 -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/instance-id)" = EXPECTED_MACHINE
[[ "$SSM_Snapshot" =~ ^[0-9]{8}-[0-9]{4}$ && "$SSM_Digest" =~ ^[a-f0-9]{64}$ && "$SSM_ConfigDigest" =~ ^[a-f0-9]{64}$ && "$SSM_Bundle" =~ ^[a-f0-9]{64}$ ]]
ref="$SSM_Snapshot $SSM_Digest $SSM_ConfigDigest $SSM_Bundle"
test "$(cat "$state/selection")" = "$ref"
slot="$SSM_Snapshot-${SSM_Digest:0:12}"
project=rougether-release
service_id() {
  local svc=$1
  docker ps -aq --no-trunc --filter "label=com.docker.compose.project=$project" --filter "label=com.docker.compose.service=$svc"
}
stop_one() {
  local svc=$1 id
  id=$(service_id "$svc")
  if [[ -n "$id" ]]; then
    [[ "$id" =~ ^[a-f0-9]{64}$ ]]
    if [[ "$(docker inspect -f '{{.State.Running}}' "$id")" = true ]]; then
      docker stop --time 45 "$id" >/dev/null
    fi
    test "$(docker inspect -f '{{.State.Running}}' "$id")" = false
  fi
}
# Persist intent before the first interruption; a failed activation cannot resume over it.
printf '%s\n' "$ref" > "$state/sealing.pending"
mv "$state/sealing.pending" "$state/sealing"
for svc in gateway db-proxy batch user-api admin-api; do stop_one "$svc"; done
db=$(service_id mysql)
if [[ -n "$db" ]]; then
  [[ "$db" =~ ^[a-f0-9]{64}$ ]]
  docker inspect "$db" | python3 -c 'import sys,json; c=json.load(sys.stdin)[0]; m=[v["Source"] for v in c["Mounts"] if v["Destination"]=="/var/lib/mysql"]; assert m==[sys.argv[1]]' "$base/release-$slot/copy/data"
  if [[ "$(docker inspect -f '{{.State.Running}}' "$db")" != true ]]; then docker start "$db" >/dev/null; fi
  sql() { docker exec -i "$db" mysql --defaults-extra-file=/run/release-root.cnf -N -B rougether; }
  for i in {1..75}; do
    if [[ "$(echo 'SELECT 1' | sql 2>/dev/null)" = 1 ]]; then break; fi
    sleep 1
  done
  test "$(echo 'SELECT 1' | sql)" = 1
  # Global super_read_only must be off to change the database flag. Only a detached DB is operated on.
  if [[ "$(echo "SELECT OPTIONS FROM information_schema.schemata_extensions WHERE schema_name=DATABASE()" | sql)" != 'READ ONLY=1' ]]; then
    echo 'SET SESSION lock_wait_timeout=7; SET GLOBAL super_read_only=OFF; SET GLOBAL read_only=OFF; ALTER DATABASE rougether READ ONLY=1;' | sql >/dev/null
  fi
  echo 'SET PERSIST read_only=ON; SET PERSIST super_read_only=ON;' | sql >/dev/null
  test "$(echo "SELECT @@global.super_read_only,OPTIONS FROM information_schema.schemata_extensions WHERE schema_name=DATABASE()" | sql)" = $'1\tREAD ONLY=1'
  echo 'SHOW BINARY LOG STATUS' | sql > "$state/seal-binlog.pending"
  test -s "$state/seal-binlog.pending"
  mv "$state/seal-binlog.pending" "$state/seal-binlog"
  stop_one mysql
else
  # For a partial warm-up with an owned selection but no running database.
  test ! -e "$state/accepted-writes" && test ! -e "$state/activating"
fi
test -z "$(docker ps -q --filter "label=com.docker.compose.project=$project")"
writes=false
if [[ -e "$state/accepted-writes" || -e "$state/activating" ]]; then writes=true; fi
printf '%s\n' "$ref" > "$state/sealed.pending"
mv "$state/sealed.pending" "$state/sealed"
printf '{"sealed":true,"runningReleaseContainers":0,"mayHaveAcceptedWrites":%s,"sourceResumed":false}\n' "$writes"
