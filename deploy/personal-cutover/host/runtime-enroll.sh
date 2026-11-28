#!/usr/bin/env bash
set -euo pipefail
umask 077
exec 9>/run/rougether-deploy.lock
flock -w 45 9
token=$(curl --noproxy '*' -fsS --max-time 3 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token)
identity=$(curl --noproxy '*' -fsS --max-time 3 -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/dynamic/instance-identity/document)
python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["region"]=="ap-northeast-2" and d["instanceId"]=="EXPECTED_MACHINE"' <<<"$identity"
base=/var/lib/rougether-personal-cutover
cd "$base"
if [[ ! -d runtime-identity ]]; then
  work=$(mktemp -d "$base/.runtime-enroll.XXXXXXXX")
  ram=$(mktemp -d /dev/shm/rougether-signing.XXXXXXXX)
  trap 'rm -rf -- "$ram" "$work"' EXIT
  openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days 400 -subj /CN=rougether-personal-runtime-ca \
    -addext basicConstraints=critical,CA:TRUE,pathlen:0 -addext keyUsage=critical,keyCertSign,cRLSign \
    -keyout "$ram/ca.key" -out "$work/ca.pem" 2>/dev/null
  printf '%s\n' 'basicConstraints=critical,CA:FALSE' 'keyUsage=critical,digitalSignature' 'subjectKeyIdentifier=hash' 'authorityKeyIdentifier=keyid' > "$ram/extensions"
  for name in user admin; do
    install -d -m 0700 "$work/$name"
    openssl req -new -newkey rsa:2048 -nodes -sha256 -subj "/CN=rougether-personal-$name-api" \
      -keyout "$work/$name/identity.key" -out "$ram/$name.csr" 2>/dev/null
    openssl x509 -req -in "$ram/$name.csr" -CA "$work/ca.pem" -CAkey "$ram/ca.key" -sha256 \
      -set_serial "0x$(openssl rand -hex 16)" -extfile "$ram/extensions" -days 397 -out "$work/$name/identity.pem" 2>/dev/null
  done
  mv -T "$work" runtime-identity
fi
for name in user admin; do
  openssl verify -CAfile runtime-identity/ca.pem "runtime-identity/$name/identity.pem" >/dev/null
  openssl x509 -in "runtime-identity/$name/identity.pem" -checkend 2592000 -noout >/dev/null
  diff -q <(openssl pkey -in "runtime-identity/$name/identity.key" -pubout) <(openssl x509 -in "runtime-identity/$name/identity.pem" -pubkey -noout) >/dev/null
done
cat runtime-identity/ca.pem
