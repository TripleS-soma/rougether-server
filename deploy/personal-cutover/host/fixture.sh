#!/usr/bin/env bash
set -euo pipefail
umask 077
exec 9>/run/rougether-deploy.lock
flock -w 60 9
cd /var/lib/rougether-personal-cutover
[[ "$SSM_Envelope" == "https://rougether-cutover-940482450790-artifacts.s3.ap-northeast-2.amazonaws.com/restore/$SSM_Snapshot/"* ]]
[[ "$SSM_Digest" =~ ^[0-9a-f]{64}$ ]]
db=rougether-furniture-fixture
net=rougether-furniture-isolated
proxy=rougether-furniture-private-proxy
dir=/var/lib/rougether-personal-cutover/furniture-fixture
test "$(df -Pk . | awk 'NR==2{print $4}')" -ge 2200000
tmp=$(mktemp -d -p /dev/shm rougether-fixture.XXXXXXXX)
trap 'rm -rf -- "$tmp"' EXIT
mkdir -m 0700 -p "$dir"
if [[ -e "$dir/ready" ]]; then
  test "$(cat "$dir/ready")" = "$SSM_Digest"
  test "$(docker inspect -f '{{.State.Running}}' "$proxy")" = true
  echo '{"fixture":true,"privateProxy":true}'
  exit 0
fi
test ! -e "$dir/data"
test -z "$(ss -H -ltn '( sport = :3306 )')"
printf '%s\n' "$SSM_Envelope" | awk '{print "url = \"" $0 "\""}' | curl --config - --proto '=https' --fail --silent --show-error --max-time 90 -o "$tmp/fixture.cms"
printf '%s  %s\n' "$SSM_Digest" "$tmp/fixture.cms" | sha256sum --check --status
openssl cms -decrypt -binary -inform DER -in "$tmp/fixture.cms" -recip transport/cert.pem -inkey transport/key.pem -out "$tmp/fixture.json"
python3 - "$dir" "$tmp/fixture.json" <<'PY'
import ipaddress,json,pathlib,re,secrets,sys
d=pathlib.Path(sys.argv[1]);m=json.load(open(sys.argv[2]))
assert m['purpose']=='synthetic-furniture' and re.fullmatch(r'[A-Za-z0-9_-]{40,64}',m['password'])
assert all(ipaddress.ip_address(x) in ipaddress.ip_network('172.31.0.0/16') for x in m['allowed']) and 1<=len(m['allowed'])<=16
assert ipaddress.ip_address(m['private']).is_private
root=secrets.token_urlsafe(36);(d/'root-password').write_text(root);(d/'client.cnf').write_text('[client]\nuser=root\npassword='+root+'\nprotocol=socket\n')
for n,k in [('ca.pem','ca'),('server.pem','certificate'),('server-key.pem','key')]:
 (d/n).write_text(m['tls'][k])
(d/'grants.sql').write_text("CREATE USER 'furniture_fixture'@'%' IDENTIFIED BY '"+m['password']+"' REQUIRE SSL; GRANT SELECT,INSERT,UPDATE,DELETE ON rougether_furniture_fixture.* TO 'furniture_fixture'@'%';")
(d/'private.addr').write_text(m['private']);(d/'allow.addr').write_text(' '.join(m['allowed']))
PY
chmod 0444 "$dir/root-password" "$dir/server.pem" "$dir/ca.pem"
chmod 0400 "$dir/client.cnf"
image=mysql@sha256:8c233bad33d520a7aae1c1e619c3a9775f4a1ba2d463d692fada04ec7f40be00
docker pull "$image" >/dev/null
mysql_uid=$(docker run --rm --network none --entrypoint id "$image" -u mysql)
chown "$mysql_uid" "$dir/server-key.pem"
chmod 0400 "$dir/server-key.pem"
stage=$(docker ps -q --filter label=com.docker.compose.project=rougether-rehearsal --filter label=com.docker.compose.service=mysql)
[[ "$stage" =~ ^[0-9a-f]{12,64}$ ]]
docker exec "$stage" sh -ec 'test "$MYSQL_DATABASE" = rougether_stage; export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysqldump -uroot --no-data --skip-lock-tables --no-tablespaces --set-gtid-purged=OFF --skip-triggers rougether_stage' > "$tmp/schema.sql"
test -s "$tmp/schema.sql"
# Create only the dedicated synthetic network, database and private TCP listener.
if ! docker network inspect "$net" >/dev/null 2>&1; then docker network create --internal --label purpose=rougether-furniture-fixture "$net" >/dev/null; fi
test "$(docker network inspect -f '{{.Internal}} {{index .Labels "purpose"}}' "$net")" = 'true rougether-furniture-fixture'
test "$(docker network inspect -f '{{len .Containers}}' "$net")" = 0
mkdir -m 0700 "$dir/data"
docker run -d --name "$db" --network "$net" --cap-drop NET_RAW --memory 768m --log-driver local --log-opt max-size=6m --restart unless-stopped -v "$dir/data:/var/lib/mysql" -v "$dir/root-password:/run/secrets/root:ro" -v "$dir/client.cnf:/run/private.cnf:ro" -v "$dir/server-key.pem:/run/server-key.pem:ro" -v "$dir/server.pem:/run/server.pem:ro" -v "$dir/ca.pem:/run/ca.pem:ro" -e MYSQL_DATABASE=rougether_furniture_fixture -e MYSQL_ROOT_PASSWORD_FILE=/run/secrets/root "$image" --skip-log-bin --event-scheduler=DISABLED --require-secure-transport=ON --ssl-ca=/run/ca.pem --ssl-cert=/run/server.pem --ssl-key=/run/server-key.pem --innodb-buffer-pool-size=128M >/dev/null
sql() { docker exec -i "$db" mysql --defaults-extra-file=/run/private.cnf --batch --skip-column-names rougether_furniture_fixture; }
for ((i=0;i<100;i++)); do
 if docker exec "$db" sh -c 'test "$(cat /proc/1/comm)" = mysqld' && [[ "$(echo 'SELECT @@event_scheduler' | sql 2>/dev/null)" = DISABLED ]]; then break; fi
 sleep 2
done
sql < "$tmp/schema.sql" >/dev/null
sql < "$dir/grants.sql" >/dev/null 2>/dev/null
rm "$dir/grants.sql"
test "$(echo 'SELECT COUNT(*) FROM users' | sql)" = 0
test "$(echo 'SELECT @@require_secure_transport' | sql)" = 1
dbaddr=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$db")
private=$(cat "$dir/private.addr"); allowed=$(cat "$dir/allow.addr")
ip -o -4 addr show | grep -Fq " $private/"
cat > "$dir/haproxy.cfg" <<EOF
global
  maxconn 12
defaults
  mode tcp
  timeout connect 4s
  timeout client 70s
  timeout server 70s
frontend private_database
  bind $private:3306
  tcp-request connection reject if !{ src $allowed }
  default_backend fixture
backend fixture
  server mysql $dbaddr:3306
EOF
chmod 0444 "$dir/haproxy.cfg"
gateway=haproxy@sha256:7816a9801a6846b912bc37e48529808e2fa87c3c47992441940f68e393ebcfeb
docker run --rm --network none --user 65534:65534 --cap-drop ALL -v "$dir/haproxy.cfg:/etc/haproxy.cfg:ro" "$gateway" haproxy -c -f /etc/haproxy.cfg >/dev/null
docker run -d --name "$proxy" --network host --read-only --user 65534:65534 --cap-drop ALL --security-opt no-new-privileges --restart unless-stopped --memory 48m -v "$dir/haproxy.cfg:/etc/haproxy.cfg:ro" "$gateway" haproxy -W -db -f /etc/haproxy.cfg >/dev/null
for ((i=0;i<10;i++)); do ss -ltnH | grep -Fq "$private:3306 " && break; sleep 1; done
ss -ltnH | grep -Fq "$private:3306 "
printf '%s\n' "$SSM_Digest" > "$dir/ready"
echo '{"fixture":true,"privateProxy":true}'
