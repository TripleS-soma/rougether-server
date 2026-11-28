#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
exec 9>/run/rougether-deploy.lock
flock -w 45 9
base=/var/lib/rougether-personal-cutover
token=$(curl --noproxy '*' -fsS --max-time 3 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token)
test "$(curl --noproxy '*' -fsS --max-time 3 -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/instance-id)" = EXPECTED_MACHINE
[[ "$SSM_Digest" =~ ^[a-f0-9]{64}$ && "$SSM_ConfigDigest" =~ ^[a-f0-9]{64}$ && "$SSM_Snapshot" =~ ^[0-9]{8}-[0-9]{4}$ ]]
slot="$SSM_Snapshot-${SSM_Digest:0:12}"
snap="$base/$slot"
release="$base/release-$slot"
config="$base/release-config/$SSM_ConfigDigest"
name="rougether-prepare-accounts-$slot"
image=mysql@sha256:8c233bad33d520a7aae1c1e619c3a9775f4a1ba2d463d692fada04ec7f40be00
test "$(cat "$snap/envelope.sha256")" = "$SSM_Digest"
cmp "$release/copy/receipt.json" "$snap/receipt.json"
python3 - "$release/verified.json" "$slot" <<'PY'
import json,sys
r=json.load(open(sys.argv[1]))
assert r['candidate']=='rougether-sealed-copy-'+sys.argv[2] and r['independentServerUUID'] and r['allTableCountsMatched'] and r['readOnly'] and r['network']=='none'
PY
for id in $(docker ps -aq); do
  docker inspect "$id" | python3 -c 'import json,sys; c=json.load(sys.stdin)[0]; matches=c["Name"]=="/"+sys.argv[2]; assert not matches or (c["Config"]["Labels"].get("rougether.prepare")==sys.argv[3] and c["HostConfig"]["NetworkMode"]=="none" and any(m["Source"]==sys.argv[1] and m["Destination"]=="/var/lib/mysql" for m in c["Mounts"])); assert matches or all(m["Source"]!=sys.argv[1] for m in c["Mounts"])' "$release/copy/data" "$name" "$SSM_Digest"
done
tmp=$(mktemp -d -p /dev/shm rougether-db-accounts.XXXXXXXX)
sql() { docker exec -i "$name" mysql --defaults-extra-file=/run/private/client.cnf -N -B; }
finish() {
  local rc=$?
  trap - EXIT
  if docker container inspect "$name" >/dev/null 2>&1; then
    docker stop --time 90 "$name" >/dev/null || exit 1
    docker rm "$name" >/dev/null || exit 1
  fi
  rm -f -- "$tmp/api.cnf" "$tmp/furniture.cnf" "$tmp/setup.sql"
  rmdir "$tmp"
  exit "$rc"
}
trap finish EXIT
if docker inspect "$name" >/dev/null 2>&1; then
  docker stop --time 90 "$name" >/dev/null
  docker rm "$name" >/dev/null
fi
python3 - "$config/db-accounts" "$tmp" <<'PY'
from pathlib import Path
import re,sys
src,out=map(Path,sys.argv[1:])
plan=["SET SESSION sql_log_bin=0;","SET GLOBAL super_read_only=OFF;","SET GLOBAL read_only=OFF;","ALTER DATABASE rougether READ ONLY=1;"]
for suffix,perms in [('api','SELECT,INSERT,UPDATE,DELETE,EXECUTE,SHOW VIEW'),('furniture','SELECT,INSERT,UPDATE,DELETE')]:
    password=(src/(suffix+'-password')).read_text()
    assert re.fullmatch(r'[A-Za-z0-9_-]{40,64}',password)
    user='rougether_'+suffix
    (out/(suffix+'.cnf')).write_text('[client]\nuser='+user+'\npassword='+password+'\nprotocol=TCP\nhost=127.0.0.1\nssl-mode=VERIFY_CA\nssl-ca=/tls/ca.pem\n')
    plan.extend(["CREATE USER IF NOT EXISTS '"+user+"'@'%' IDENTIFIED BY '"+password+"' REQUIRE SSL;","GRANT "+perms+" ON rougether.* TO '"+user+"'@'%';"])
plan.append("SET GLOBAL super_read_only=ON;")
(out/'setup.sql').write_text('\n'.join(plan)+'\n')
PY
docker run -d --name "$name" --network none --restart=no --memory 1152m --label "rougether.prepare=$SSM_Digest" \
  -v "$release/copy/data:/var/lib/mysql" -v "$snap/client/client.cnf:/run/private/client.cnf:ro" -v "$tmp/api.cnf:/auth/api.cnf:ro" -v "$tmp/furniture.cnf:/auth/furniture.cnf:ro" -v "$config/database:/tls:ro" \
  "$image" --ssl-cert=/tls/server.pem --ssl-key=/tls/server-key.pem --ssl-ca=/tls/ca.pem --require-secure-transport=ON --event-scheduler=DISABLED --log-bin=binlog --sync-binlog=1 --innodb-buffer-pool-size=128M >/dev/null
test "$(docker inspect -f '{{.HostConfig.NetworkMode}}' "$name")" = none
for i in {1..60}; do
  if [[ "$(echo 'SELECT @@super_read_only' | sql 2>/dev/null)" = 1 ]]; then break; fi
  sleep 1
done
test "$(echo "SELECT @@super_read_only AND EXISTS (SELECT 1 FROM performance_schema.persisted_variables WHERE VARIABLE_NAME='super_read_only' AND VARIABLE_VALUE='ON')" | sql)" = 1
if ! sql < "$tmp/setup.sql" >/dev/null 2>/dev/null; then echo 'Isolated account setup failed' >&2; exit 1; fi
test "$(echo "SELECT @@super_read_only,OPTIONS FROM information_schema.schemata_extensions WHERE schema_name='rougether'" | sql)" = $'1\tREAD ONLY=1'
for role in api furniture; do
  probe=$(docker exec -i "$name" mysql --defaults-extra-file="/auth/$role.cnf" -N -B rougether <<'SQL'
SELECT CURRENT_USER(); SHOW SESSION STATUS LIKE 'Ssl_cipher'; SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;
SQL
)
  python3 - "$role" "$probe" <<'PY'
import sys
lines=sys.argv[2].splitlines()
assert len(lines)==3 and lines[0]=='rougether_'+sys.argv[1]+'@%' and lines[1].startswith('Ssl_cipher\t') and lines[1].split('\t')[1] and lines[2]=='109'
PY
done
echo "SHOW GRANTS FOR 'rougether_api'@'%'; SHOW GRANTS FOR 'rougether_furniture'@'%';" | sql | python3 -c 'import sys
rows=sys.stdin.read().splitlines()
assert len(rows)==4 and all("WITH GRANT OPTION" not in row for row in rows)
for user,perms in [("rougether_api",{"SELECT","INSERT","UPDATE","DELETE","EXECUTE","SHOW VIEW"}),("rougether_furniture",{"SELECT","INSERT","UPDATE","DELETE"})]:
 target=[row for row in rows if row.endswith(" TO `"+user+"`@`%`")]
 assert len(target)==2 and sum(row.startswith("GRANT USAGE ON *.* ") for row in target)==1
 row,=[row for row in target if " ON `rougether`.* " in row]
 assert set(row.split(" ON ",1)[0][6:].split(", "))==perms'
docker stop --time 90 "$name" >/dev/null
test "$(docker inspect -f '{{.State.ExitCode}} {{.State.OOMKilled}}' "$name")" = '0 false'
docker rm "$name" >/dev/null
printf '{"snapshot":"%s","digest":"%s","config":"%s","tlsLogins":2,"schemaReadOnly":true,"globalReadOnly":true,"network":"none","checkedGrants":true}\n' "$SSM_Snapshot" "$SSM_Digest" "$SSM_ConfigDigest" > "$release/accounts.pending"
mv "$release/accounts.pending" "$release/accounts.json"
cat "$release/accounts.json"
