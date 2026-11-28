#!/usr/bin/env bash
# 정식 개인 배포 문서에서만 호출한다. 검증된 격리 후보를 보존하면서 실제 서비스용 복사본을 만든다.
set -Eeuo pipefail
umask 077
exec 9>/run/rougether-deploy.lock
flock -w 45 9
base=/var/lib/rougether-personal-cutover
[[ "$SSM_Snapshot" =~ ^[0-9]{8}-[0-9]{4}$ && "$SSM_Digest" =~ ^[a-f0-9]{64}$ ]]
slot="$SSM_Snapshot-${SSM_Digest:0:12}"
[[ "$slot" =~ ^[0-9]{8}-[0-9]{4}-[0-9a-f]{12}$ ]]
snap="$base/$slot"
release="$base/release-$slot"
candidate="rougether-candidate-$slot"
img=mysql@sha256:8c233bad33d520a7aae1c1e619c3a9775f4a1ba2d463d692fada04ec7f40be00
test "$(cat "$snap/envelope.sha256")" = "$SSM_Digest"
test -d "$snap/data" && test ! -L "$snap/data"
test "$(docker inspect -f '{{.Config.Image}} {{.HostConfig.NetworkMode}} {{len .HostConfig.PortBindings}}' "$candidate")" = "$img none 0"
test "$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Source}}{{end}}{{end}}' "$candidate")" = "$snap/data"
python3 - "$snap/receipt.json" "$candidate" <<'PY'
import json,sys
r=json.load(open(sys.argv[1]))
assert r['candidate']==sys.argv[2] and r['network']=='none' and r['readOnly'] and r['allTableCountsMatched']
assert r['orphanViolations']==0 and r['flywayMatched'] and r['tables']>0
PY
install -d -m 0700 "$release"
if [[ -f "$release/verified.json" ]]; then
  cmp "$release/copy/receipt.json" "$snap/receipt.json"
  test -f "$release/copy/data/ibdata1" && test -f "$release/copy/data/mysqld-auto.cnf"
  cat "$release/verified.json"
  exit 0
fi
# A partial copy is never opened. A retry uses a fresh directory while keeping failed output for inspection.
if [[ "$(docker inspect -f '{{.State.Running}}' "$candidate")" != true ]]; then docker start "$candidate" >/dev/null; fi
sql() { docker exec -i "$candidate" mysql --defaults-extra-file=/run/private/client.cnf -N -B; }
for attempt in {1..50}; do
  if [[ "$(echo 'SELECT @@global.super_read_only' | sql 2>/dev/null)" = 1 ]]; then break; fi
  sleep 1
done
test "$(echo "SELECT @@global.super_read_only AND EXISTS (SELECT 1 FROM performance_schema.persisted_variables WHERE VARIABLE_NAME='super_read_only' AND VARIABLE_VALUE='ON')" | sql)" = 1
# The old candidate always stays disconnected. Restart it even when the disk fills or the copy fails.
recover() {
  local rc=$?
  trap - EXIT
  if [[ "$(docker inspect -f '{{.State.Running}}' "$candidate")" != true ]]; then
    docker start "$candidate" >/dev/null || exit 1
  fi
  exit "$rc"
}
trap recover EXIT
if [[ ! -d "$release/copy" ]]; then
  needed=$(du -sk "$snap/data" | awk '{print $1}')
  free=$(df -Pk "$release" | awk 'NR==2{print $4}')
  (( free > needed + 1800000 ))
  pending=$(mktemp -d "$release/.copy.XXXXXXXX")
  docker stop --time 90 "$candidate" >/dev/null
  test "$(docker inspect -f '{{.State.Running}} {{.State.ExitCode}} {{.State.OOMKilled}}' "$candidate")" = 'false 0 false'
  cp -a --reflink=auto "$snap/data" "$pending/data"
  test -f "$pending/data/ibdata1" && test -f "$pending/data/mysqld-auto.cnf"
  cmp "$snap/data/mysqld-auto.cnf" "$pending/data/mysqld-auto.cnf"
  # MySQL assigns the service database an independent server UUID on first start.
  rm "$pending/data/auto.cnf"
  cp "$snap/receipt.json" "$pending/receipt.json"
  mv "$pending" "$release/copy"
  docker start "$candidate" >/dev/null
fi
cmp "$release/copy/receipt.json" "$snap/receipt.json"
test -f "$release/copy/data/ibdata1"
probe=rougether-sealed-copy-$slot
tmp=$(mktemp -d -p /dev/shm rougether-sealed.XXXXXXXX)
finish() {
  local rc=$?
  trap - EXIT
  if docker container inspect "$probe" >/dev/null 2>&1; then
    test "$(docker inspect -f '{{index .Config.Labels "rougether.sealed"}} {{.HostConfig.NetworkMode}}' "$probe")" = "$SSM_Digest none" || exit 1
    docker stop --time 90 "$probe" >/dev/null || exit 1
    docker rm "$probe" >/dev/null || exit 1
  fi
  rm -f -- "$tmp/metadata.json" "$tmp/verify.py" "$tmp/verified.json"
  rmdir "$tmp" || exit 1
  exit "$rc"
}
trap finish EXIT
if docker container inspect "$probe" >/dev/null 2>&1; then
  test "$(docker inspect -f '{{index .Config.Labels "rougether.sealed"}} {{.HostConfig.NetworkMode}}' "$probe")" = "$SSM_Digest none"
  docker start "$probe" >/dev/null
else
  docker run -d --name "$probe" --label "rougether.sealed=$SSM_Digest" --network none --restart=no --memory 1g \
    --log-driver local --log-opt max-size=6m -v "$release/copy/data:/var/lib/mysql" -v "$snap/client/client.cnf:/run/private/client.cnf:ro" \
    "$img" --log-bin=binlog --sync-binlog=1 --innodb-flush-log-at-trx-commit=1 --binlog-expire-logs-seconds=259200 \
    --event-scheduler=DISABLED --require-secure-transport=ON --innodb-buffer-pool-size=128M >/dev/null
fi
clone_sql() { docker exec -i "$probe" mysql --defaults-extra-file=/run/private/client.cnf -N -B; }
for attempt in {1..65}; do
  if [[ "$(echo 'SELECT @@super_read_only' | clone_sql 2>/dev/null)" = 1 ]]; then break; fi
  sleep 1
done
test "$(echo 'SELECT @@super_read_only,@@log_bin,@@sync_binlog,@@innodb_flush_log_at_trx_commit,@@require_secure_transport,@@event_scheduler' | clone_sql)" = $'1\t1\t1\t1\t1\tDISABLED'
source_uuid=$(echo 'SELECT @@server_uuid' | sql)
clone_uuid=$(echo 'SELECT @@server_uuid' | clone_sql)
[[ "$source_uuid" =~ ^[0-9a-f-]{36}$ && "$clone_uuid" =~ ^[0-9a-f-]{36}$ && "$source_uuid" != "$clone_uuid" ]]
test "$(docker inspect -f '{{.HostConfig.NetworkMode}} {{len .HostConfig.PortBindings}}' "$probe")" = 'none 0'
openssl cms -decrypt -binary -inform DER -in "$snap/envelope.cms" -recip "$base/transport/cert.pem" -inkey "$base/transport/key.pem" | tar -xO -f - metadata.json > "$tmp/metadata.json"
# SEALED_DB_VERIFIER
python3 "$tmp/verify.py" "$probe" "$tmp/metadata.json" > "$tmp/verified.json"
python3 - "$tmp/verified.json" "$release/verified.pending" <<'PY_RESULT'
import json,sys
r=json.load(open(sys.argv[1]))
assert r['allTableCountsMatched'] and r['readOnly'] and r['network']=='none'
r.update(zeroPublicPorts=True,independentServerUUID=True,binlogEnabled=True,fsyncOnCommit=True,secureTransportRequired=True,sourceDetached=True)
with open(sys.argv[2],"w") as f: json.dump(r,f)
PY_RESULT
docker stop --time 90 "$probe" >/dev/null
test "$(docker inspect -f '{{.State.ExitCode}} {{.State.OOMKilled}}' "$probe")" = '0 false'
docker rm "$probe" >/dev/null
mv "$release/verified.pending" "$release/verified.json"
cat "$release/verified.json"
