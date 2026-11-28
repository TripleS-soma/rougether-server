#!/usr/bin/env bash
set -euo pipefail
umask 077
exec 9>/run/rougether-deploy.lock
flock -w 45 9
token=$(curl --noproxy '*' -fsS --max-time 3 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token)
identity=$(curl --noproxy '*' -fsS --max-time 3 -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/dynamic/instance-identity/document)
python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["region"]=="ap-northeast-2" and d["instanceId"]=="EXPECTED_MACHINE"' <<<"$identity"
base=/var/lib/rougether-personal-cutover
install -d -m 0700 "$base"
cd "$base"
if [[ ! -e transport ]]; then
  tmp=$(mktemp -d .transport.XXXXXXXX)
  trap 'rm -rf -- "$tmp"' EXIT
  openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days 365 -subj /CN=Rougether-private-restore -keyout "$tmp/key.pem" -out "$tmp/cert.pem" 2>/dev/null
  chmod 600 "$tmp/"*.pem
  mv -T "$tmp" transport
fi
diff -q <(openssl pkey -in transport/key.pem -pubout) <(openssl x509 -in transport/cert.pem -pubkey -noout) >/dev/null
openssl x509 -in transport/cert.pem -checkend 86400 -noout >/dev/null
cat transport/cert.pem
