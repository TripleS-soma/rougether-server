#!/usr/bin/env bash
# 컨테이너 네임스페이스에서 DB 호스트 하나의 경로만 남긴 뒤 JVM을 시작한다.
set -Eeuo pipefail
id=$1
service=$2
private=$3
case "$service" in user-api) address=172.29.242.2 ;; admin-api) address=172.29.242.3 ;; *) exit 2 ;; esac
[[ "$id" =~ ^[0-9a-f]{64}$ ]]
python3 - "$private" <<'PY'
import sys,ipaddress
assert ipaddress.IPv4Address(sys.argv[1]) in ipaddress.IPv4Network("172.16.0.0/12")
PY
test "$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}} {{index .Config.Labels "com.docker.compose.service"}} {{.HostConfig.RestartPolicy.Name}}' "$id")" = "rougether-release $service no"
docker inspect "$id" | python3 -c 'import sys,json; networks=json.load(sys.stdin)[0]["NetworkSettings"]["Networks"]; assert list(networks)==["rougether-release_clients"]; n=networks["rougether-release_clients"]; assert n["IPAddress"]==sys.argv[1] and n["Gateway"]=="172.29.242.1"' "$address"
pid=$(docker inspect -f '{{.State.Pid}}' "$id")
[[ "$pid" =~ ^[1-9][0-9]*$ ]]
# Keep the namespace handle tied to this specific process if the container exits.
exec {netfd}<"/proc/$pid/ns/net"
net="/proc/$$/fd/$netfd"
test "$(readlink "$net")" != "$(readlink /proc/self/ns/net)"
inside() { nsenter --net="$net" -- ip "$@"; }
inside route replace "$private/32" via 172.29.242.1 dev eth0
if [[ -n "$(inside -4 route list default)" ]]; then inside -4 route del default; fi
test -z "$(inside -4 route list default)"
test -z "$(inside -6 route list default)"
inside -4 route get "$private" >/dev/null
if inside -4 route get 1.1.1.1 >/dev/null 2>&1; then exit 1; fi
test "$(docker inspect -f '{{.State.Pid}}' "$id")" = "$pid"
docker exec "$id" touch /tmp/.release-network-ready
