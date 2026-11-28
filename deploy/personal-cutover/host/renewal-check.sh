#!/usr/bin/env bash
set -euo pipefail
umask 077
exec 9>/run/rougether-deploy.lock
flock -w 45 9
identity=/var/lib/rougether-personal-cutover/runtime-identity
cd "$identity"
test -s checks/receipt.json
test -x aws_signing_helper
printf '%s  aws_signing_helper\n' 3553fdc515e5682541a9ebdd37031ec7f461169aa13ccbb6fbd5b7259644113d | sha256sum --check --status
install -d -m 0700 renewal
cd renewal
# RENEWAL_JAVA_SOURCE
revision=$(sha256sum RenewalProbe.java | cut -c1-12)
image=eclipse-temurin@sha256:d1b500f1a2a8e2cb1b0fa84e21c4a52963050cbd46b8a53fcb6a8eec0f57675f
results=()
for role in user admin; do
  probe="rougether-renewal-$revision-$role"
  dir="$PWD/$revision-$role"
  if [[ -f "$dir/verified" ]]; then results+=(verified); continue; fi
  install -d -m 0700 "$dir"
  if ! docker container inspect "$probe" >/dev/null 2>&1; then
    test "$(df -Pk . | awk 'NR==2{print $4}')" -gt 700000
    python3 - "$dir" "$role" <<'PY_LIB'
import hashlib,pathlib,sys,zipfile
directory=pathlib.Path(sys.argv[1]); role=sys.argv[2]
jar=pathlib.Path('/home/ubuntu/rougether-stage')/(role+'-api.jar')
expected={'user':'8ae9387d6b4c8bb0741a058170c43c15518db6c00116c03fc77d4dfb91f094a2','admin':'2d0f2b7b33cd00ad7201ae2a7504f9c94c363374e915a27f900bbeeb0a92677b'}
h=hashlib.sha256()
with jar.open('rb') as stream:
    for chunk in iter(lambda: stream.read(1048576),b''): h.update(chunk)
assert h.hexdigest()==expected[role]
with zipfile.ZipFile(jar) as archive:
    for f in archive.namelist():
        if f.startswith('BOOT-INF/lib/') and f.endswith('.jar'):
            (directory/pathlib.Path(f).name).write_bytes(archive.read(f))
PY_LIB
    install -m 0400 RenewalProbe.java "$dir/RenewalProbe.java"
    docker run -d --name "$probe" --label rougether.purpose=credential-renewal --restart=no \
      --log-driver local --log-opt max-size=1m --memory 320m --cpus 0.6 --cap-drop ALL \
      --security-opt no-new-privileges --read-only --tmpfs /tmp:size=32m \
      -v "$identity/$role:/run/rougether-identity:ro" -v "$identity/aws_signing_helper:/usr/local/bin/aws_signing_helper:ro" \
      -v "$dir:/check:ro" -e AWS_EC2_METADATA_DISABLED=true -e AWS_CONFIG_FILE=/run/rougether-identity/config \
      --entrypoint java "$image" -Xmx96m -cp '/check/*' /check/RenewalProbe.java "$role" >/dev/null
  fi
  test "$(docker inspect -f '{{index .Config.Labels "rougether.purpose"}}' "$probe")" = credential-renewal
  for ((i=0;i<80;i++)); do
    if docker logs "$probe" 2>/dev/null | grep -Fxq FIRST_REQUEST_OK; then break; fi
    test "$(docker inspect -f '{{.State.Running}}' "$probe")" = true || { echo "Initial $role request failed" >&2; exit 1; }
    sleep 1
  done
  docker logs "$probe" 2>/dev/null | grep -Fxq FIRST_REQUEST_OK
  if [[ $(docker inspect -f '{{.State.Running}}' "$probe") == true ]]; then
    results+=(running)
  else
    test "$(docker inspect -f '{{.State.ExitCode}}' "$probe")" = 0
    docker logs "$probe" 2>/dev/null | grep -Fxq RENEWED_REQUEST_OK
    docker rm "$probe" >/dev/null
    rm -f -- "$dir/"*.jar "$dir/RenewalProbe.java"
    touch "$dir/verified"
    results+=(verified)
  fi
done
printf '{"user":"%s","admin":"%s","firstRequestsVerified":true}\n' "${results[@]}"
