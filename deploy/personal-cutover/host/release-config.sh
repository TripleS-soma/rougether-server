#!/usr/bin/env bash
# 대상 호스트 전용 봉투를 서비스별 비공개 후보 디렉터리에 준비한다.
set -Eeuo pipefail
umask 077
exec 9>/run/rougether-deploy.lock
flock -w 45 9
base=/var/lib/rougether-personal-cutover
token=$(curl --noproxy '*' -fsS --max-time 3 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token)
machine=$(curl --noproxy '*' -fsS --max-time 3 -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/instance-id)
test "$machine" = EXPECTED_MACHINE
[[ "$SSM_Digest" =~ ^[a-f0-9]{64}$ ]]
dest="$base/release-config/$SSM_Digest"
install -d -m 0700 "$base/release-config"
check_files() {
  local d="$1"
  test -s "$d/user-api/JWT_SECRET"
  test ! -e "$d/admin-api/JWT_SECRET"
  test ! -e "$d/batch/JWT_SECRET"
  test -s "$d/admin-api/ADMIN_ORIGIN_SECRET"
  test ! -e "$d/user-api/ADMIN_ORIGIN_SECRET"
  test -s "$d/database/server-key.pem"
  test ! -e "$d/user-api/server-key.pem"
  test -z "$(find "$d" -type l -print -quit)"
  test -z "$(find "$d" -type f -perm /077 -print -quit)"
  cmp -s "$d/admin-api/ADMIN_ORIGIN_SECRET" "$d/gateway/admin-origin"
  openssl verify -CAfile "$d/database/ca.pem" -purpose sslserver "$d/database/server.pem" >/dev/null
  diff -q <(openssl x509 -in "$d/database/server.pem" -pubkey -noout) <(openssl pkey -in "$d/database/server-key.pem" -pubout) >/dev/null
  openssl x509 -in "$d/database/server.pem" -checkip "$private" -noout | grep -q 'does match'
  openssl x509 -in "$d/database/server.pem" -checkend 86400 -noout >/dev/null
}
private=$(curl --noproxy '*' -fsS --max-time 3 -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/local-ipv4)
if [[ ! -d "$dest" ]]; then
  work=$(mktemp -d "$base/release-config/.pending.XXXXXXXX")
  trap 'rm -rf -- "$work"' EXIT
  printf '%s\n' "$SSM_Envelope" | awk '{print "url = \"" $0 "\""}' | curl --config - --proto '=https' --fail --silent --max-time 45 -o "$work/config.cms"
  printf '%s  %s\n' "$SSM_Digest" "$work/config.cms" | sha256sum --check --status
  # CONFIG_RENDERER
  openssl cms -decrypt -binary -inform DER -in "$work/config.cms" -recip "$base/transport/cert.pem" -inkey "$base/transport/key.pem" | python3 "$work/render.py" "$work/config" >/dev/null
  mysql_uid=$(docker run --rm --network none --entrypoint id mysql@sha256:8c233bad33d520a7aae1c1e619c3a9775f4a1ba2d463d692fada04ec7f40be00 -u mysql)
  [[ "$mysql_uid" =~ ^[1-9][0-9]*$ ]]
  chown -R "$mysql_uid" "$work/config/database"
  check_files "$work/config"
  grep -Fq "jdbc:mysql://$private:3306/rougether?" "$work/config/user-api/DB_URL"
  cmp -s "$work/config/user-api/DB_URL" "$work/config/batch/DB_URL"
  mv -T "$work/config" "$dest"
fi
check_files "$dest"
printf '{"sealedConfig":true,"connected":false,"services":3,"privateTls":true,"digest":"%s"}\n' "$SSM_Digest"
