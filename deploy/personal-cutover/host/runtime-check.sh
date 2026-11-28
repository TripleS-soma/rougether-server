#!/usr/bin/env bash
set -euo pipefail
umask 077
exec 9>/run/rougether-deploy.lock
flock -w 45 9
cd /var/lib/rougether-personal-cutover/runtime-identity
base=$PWD
for id in "$SSM_AnchorId" "$SSM_UserProfileId" "$SSM_AdminProfileId"; do
  [[ $id =~ ^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$ ]]
done
[[ $(uname -m) == x86_64 ]]
account=940482450790
region=ap-northeast-2
anchor="arn:aws:rolesanywhere:$region:$account:trust-anchor/$SSM_AnchorId"
tmp=$(mktemp -d "$base/.probe.XXXXXXXX")
trap 'rm -rf -- "$tmp"' EXIT
hash=3553fdc515e5682541a9ebdd37031ec7f461169aa13ccbb6fbd5b7259644113d
if [[ ! -f aws_signing_helper ]]; then
  curl --proto '=https' --fail --silent --show-error --max-time 120 \
    'https://rolesanywhere.amazonaws.com/releases/1.8.6/X86_64/Linux/Amzn2023/aws_signing_helper' -o "$tmp/helper"
  printf '%s  %s\n' "$hash" "$tmp/helper" | sha256sum -c --status
  install -m 0755 "$tmp/helper" aws_signing_helper
fi
printf '%s  %s\n' "$hash" aws_signing_helper | sha256sum -c --status
for name in user admin; do
  profile="$SSM_UserProfileId"
  if [[ "$name" == admin ]]; then profile="$SSM_AdminProfileId"; fi
  profile_arn="arn:aws:rolesanywhere:$region:$account:profile/$profile"
  role_arn="arn:aws:iam::$account:role/rougether-personal-$name-api"
  openssl verify -CAfile ca.pem "$name/identity.pem" >/dev/null
  openssl x509 -in "$name/identity.pem" -checkend 2592000 -noout >/dev/null
  printf '[default]\nregion = %s\ncredential_process = /usr/local/bin/aws_signing_helper credential-process --certificate /run/rougether-identity/identity.pem --private-key /run/rougether-identity/identity.key --trust-anchor-arn %s --profile-arn %s --role-arn %s --session-duration 3600\n' "$region" "$anchor" "$profile_arn" "$role_arn" > "$tmp/config"
  install -m 0400 "$tmp/config" "$name/config"
done
# Probe independent temporary sessions without writing the returned credentials to disk.
python3 - "$base" <<'PY_CHECK'
import datetime,json,pathlib,shlex,subprocess,sys
from configparser import ConfigParser
base=pathlib.Path(sys.argv[1])
def args(role,identity):
    config=ConfigParser();config.read(base/role/'config')
    args=shlex.split(config['default']['credential_process'])
    args[0]=str(base/'aws_signing_helper')
    return [s.replace('/run/rougether-identity/',str(base/identity)+'/') for s in args]
for role,other in [('user','admin'),('admin','user')]:
    sessions=[]
    for _ in range(2):
        p=subprocess.run(args(role,role),capture_output=True)
        if p.returncode:raise SystemExit('Unable to issue '+role+' session')
        result=json.loads(p.stdout)
        ttl=(datetime.datetime.fromisoformat(result['Expiration'].replace('Z','+00:00'))-datetime.datetime.now(datetime.timezone.utc)).total_seconds()
        assert result['Version']==1 and 3450<ttl<=3630 and result['SessionToken']
        sessions.append(result['AccessKeyId'])
    assert sessions[0]!=sessions[1]
    assert subprocess.run(args(role,other),capture_output=True).returncode!=0
PY_CHECK
install -d -m 0700 checks
if [[ -s checks/receipt.json ]]; then cat checks/receipt.json; exit 0; fi
# JAVA_PROBE_SOURCE
python3 - "$tmp" <<'PY_JARS'
import pathlib,sys,zipfile
root=pathlib.Path('/home/ubuntu/rougether-stage')
for role in ['user','admin']:
    directory=pathlib.Path(sys.argv[1])/role
    directory.mkdir()
    with zipfile.ZipFile(root/(role+'-api.jar')) as archive:
        for name in archive.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                (directory/pathlib.Path(name).name).write_bytes(archive.read(name))
PY_JARS
image='eclipse-temurin@sha256:d1b500f1a2a8e2cb1b0fa84e21c4a52963050cbd46b8a53fcb6a8eec0f57675f'
docker run --rm --network none --memory 384m --cpus 1 --cap-drop ALL --security-opt no-new-privileges \
  -v "$tmp:/lab" "$image" javac -proc:none -cp '/lab/user/*' -d /lab /lab/RuntimeProbe.java >/dev/null
run_probe() {
  local name=$1
  shift
  docker run --rm --memory 384m --cpus 1 --cap-drop ALL --security-opt no-new-privileges --read-only --tmpfs /tmp:size=32m \
    -v "$base/$name:/run/rougether-identity:ro" -v "$base/aws_signing_helper:/usr/local/bin/aws_signing_helper:ro" \
    -v "$tmp:/lab:ro" -e AWS_EC2_METADATA_DISABLED=true -e AWS_CONFIG_FILE=/run/rougether-identity/config -e AWS_REGION=ap-northeast-2 \
    --entrypoint java "$image" -XX:MaxRAMPercentage=50 -cp "/lab:/lab/$name/*" RuntimeProbe "$@"
}
if [[ ! -f checks/id ]]; then cat /proc/sys/kernel/random/uuid > checks/id; fi
id=$(cat checks/id)
if [[ ! -f checks/version ]]; then
  version=$(run_probe user upload "$id")
  [[ $version =~ ^[A-Za-z0-9_./+=-]{8,256}$ ]]
  printf '%s' "$version" > checks/version
fi
if [[ ! -f checks/deleted ]]; then
  [[ $(run_probe admin moderate "$id" "$(cat checks/version)") == verified ]]
  [[ $(run_probe user cleanup "$id" "$(cat checks/version)") == verified ]]
  touch checks/deleted
fi
if [[ ! -f checks/message ]]; then
  # If SQS accepted a send but we lost its response, reconcile the queue before retrying.
  (set -o noclobber; : > checks/publishing)
  mid=$(run_probe user publish "$id")
  [[ $mid =~ ^[0-9a-f-]{36}$ ]]
  printf '%s' "$mid" > checks/message
fi
python3 - <<'PY_RECEIPT'
import json,pathlib,uuid
p=pathlib.Path('checks')
receipt={'runtimeIdentity':True,'credentialSeconds':3600,'reissued':True,'crossRoleDenied':True,
         'userMediaReadWriteDelete':True,'adminMediaRead':True,'adminMediaDeleteDenied':True,
         'messageId':str(uuid.UUID((p/'message').read_text()))}
(p/'receipt.pending').write_text(json.dumps(receipt))
(p/'receipt.pending').replace(p/'receipt.json')
PY_RECEIPT
cat checks/receipt.json
