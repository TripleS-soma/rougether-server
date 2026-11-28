#!/usr/bin/env bash
set -euo pipefail
umask 077
exec 9>/run/rougether-deploy.lock
flock -w 45 9
base=/var/lib/rougether-personal-cutover
cd "$base"
test -f transport/key.pem
[[ "$SSM_Snapshot" =~ ^[0-9]{8}-[0-9]{4}$ ]]
[[ "$SSM_Digest" =~ ^[a-f0-9]{64}$ ]]
[[ "$SSM_Envelope" == "https://rougether-cutover-940482450790-artifacts.s3.ap-northeast-2.amazonaws.com/restore/$SSM_Snapshot/"* ]]
readonly img=mysql@sha256:8c233bad33d520a7aae1c1e619c3a9775f4a1ba2d463d692fada04ec7f40be00
name="rougether-candidate-$SSM_Snapshot"
snap="$base/$SSM_Snapshot"
test "$(df -Pk "$base" | awk 'NR==2{print $4}')" -ge 4000000
install -d -m 0700 "$snap"
if [[ -e "$snap/receipt.json" ]]; then
  test "$(cat "$snap/envelope.sha256")" = "$SSM_Digest"
  test "$(docker inspect -f '{{.State.Running}} {{.HostConfig.NetworkMode}}' "$name")" = 'true none'
  docker exec "$name" mysql --defaults-extra-file=/run/private/client.cnf -Nse 'SELECT @@GLOBAL.super_read_only' | grep -qx 1
  cat "$snap/receipt.json"
  exit 0
fi
if docker inspect "$name" >/dev/null 2>&1; then
  echo 'An incomplete candidate exists; retain it and use a fresh snapshot label.' >&2; exit 1
fi
test ! -e "$snap/data"
tmp=$(mktemp -d -p /dev/shm rougether-cutover.XXXXXXXX)
trap 'rm -rf -- "$tmp"' EXIT
printf '%s\n' "$SSM_Envelope" | awk '{print "url = \"" $0 "\""}' | curl --config - --proto '=https' --fail --silent --show-error --max-time 300 -o "$snap/envelope.cms"
printf '%s  %s\n' "$SSM_Digest" "$snap/envelope.cms" | sha256sum --check --status
printf '%s\n' "$SSM_Digest" > "$snap/envelope.sha256"
decrypt() { openssl cms -decrypt -binary -inform DER -in "$snap/envelope.cms" -recip transport/cert.pem -inkey transport/key.pem; }
decrypt | tar -xO -f - metadata.json > "$tmp/metadata.json"
install -d -m 0700 "$snap/client"
python3 - "$tmp/metadata.json" "$snap/client" <<'PY'
import json,pathlib,re,sys
m=json.load(open(sys.argv[1])); p=pathlib.Path(sys.argv[2]); pw=m['rootPassword']
assert re.fullmatch(r'[A-Za-z0-9_-]{40,64}',pw) and re.fullmatch(r'utf8mb4_[a-z0-9_]+',m['collation'])
(p/'root-password').write_text(pw)
(p/'client.cnf').write_text('[client]\nuser=root\npassword='+pw+'\nprotocol=socket\n')
PY
# The official image reads the root file during initialization as its mysql user.
chmod 0444 "$snap/client/root-password"
chmod 0400 "$snap/client/client.cnf"
collation=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["collation"])' "$tmp/metadata.json")
install -d -m 0700 "$snap/data"
docker run -d --name "$name" --network none --memory 1152m --log-driver local --log-opt max-size=8m --restart unless-stopped --mount "type=bind,src=$snap/data,dst=/var/lib/mysql" --mount "type=bind,src=$snap/client/root-password,dst=/run/secrets/root-password,readonly" --mount "type=bind,src=$snap/client/client.cnf,dst=/run/private/client.cnf,readonly" -e MYSQL_DATABASE=rougether -e MYSQL_ROOT_PASSWORD_FILE=/run/secrets/root-password "$img" --skip-log-bin --event-scheduler=DISABLED --innodb-buffer-pool-size=192M --collation-server="$collation" --character-set-server=utf8mb4 >/dev/null
sql() { docker exec -i "$name" mysql --defaults-extra-file=/run/private/client.cnf -N -B rougether; }
for ((i=0;i<150;i++)); do
  if docker exec "$name" sh -c 'test "$(cat /proc/1/comm)" = mysqld' && [[ "$(echo 'SELECT @@event_scheduler' | sql 2>/dev/null)" = DISABLED ]]; then break; fi
  sleep 2
done
[[ "$(echo 'SHOW TABLES' | sql)" = '' ]]
# mysql can include failed customer SQL in stderr. Keep the error stream off SSM and Actions.
decrypt | tar -xO -f - database.sql | sql >/dev/null 2>/dev/null
echo 'SET GLOBAL super_read_only=ON' | sql
[[ "$(echo 'SELECT @@global.super_read_only' | sql)" = 1 ]]
test "$(docker inspect -f '{{.HostConfig.NetworkMode}} {{len .HostConfig.PortBindings}}' "$name")" = 'none 0'
python3 "$tmp/verify.py" "$name" "$tmp/metadata.json" > "$snap/receipt.pending"
mv "$snap/receipt.pending" "$snap/receipt.json"
cat "$snap/receipt.json"
