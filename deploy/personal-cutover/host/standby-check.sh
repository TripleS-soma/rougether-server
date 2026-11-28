#!/usr/bin/env bash
set -euo pipefail
umask 077
exec 9>/run/rougether-deploy.lock
flock -w 45 9
token=$(curl --noproxy '*' -fsS --max-time 3 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token)
machine=$(curl --noproxy '*' -fsS --max-time 3 -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/instance-id)
test "$machine" = EXPECTED_MACHINE
base=/var/lib/rougether-personal-cutover
test "$(df -Pk "$base" | awk 'NR==2{print $4}')" -ge 2200000
test "$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)" -ge 2650000
tmp=$(mktemp -d "$base/.standby-tools.XXXXXXXX")
cd "$tmp"
trap 'rm -f -- "$tmp/compose.apps.yaml" "$tmp/compose.standby.yaml" "$tmp/standby_check.py"; rmdir -- "$tmp"' EXIT
# STANDBY_TOOLS
python3 standby_check.py --host-probe --jars /home/ubuntu/rougether-stage --work-dir "$base"
