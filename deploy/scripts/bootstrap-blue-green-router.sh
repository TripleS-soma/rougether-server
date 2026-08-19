#!/usr/bin/env bash
set -Eeuo pipefail

# Existing-host one-time migration from legacy fixed-port containers to Nginx + blue slots.
# Without --activate it installs/tests the runtime and cold-starts each candidate without
# touching ports 8080/8081. The explicit activation is the only path that changes routing.

ACTIVATE=false
if [ "${1:-}" = "--activate" ]; then
  ACTIVATE=true
elif [ "$#" -ne 0 ]; then
  echo "usage: $0 [--activate]" >&2
  exit 2
fi

ENV_DIR="${ROUGETHER_ENV_DIR:-/etc/rougether}"
SYSTEMD_DIR="${ROUGETHER_SYSTEMD_DIR:-/etc/systemd/system}"
NGINX_CONFIG_DIR="${ROUGETHER_NGINX_CONFIG_DIR:-/etc/nginx/conf.d}"
NGINX_CONFIG_FILE="$NGINX_CONFIG_DIR/rougether.conf"
STATE_FILE="$ENV_DIR/deploy-state.env"
USER_IMAGE=""
ADMIN_IMAGE=""
BATCH_IMAGE=""
DEPLOYED_SHA="bootstrap"
user_legacy_stopped=false
admin_legacy_stopped=false

[ "$(id -u)" -eq 0 ] || { echo "run as root" >&2; exit 1; }
[ -f "$STATE_FILE" ] || { echo "missing $STATE_FILE" >&2; exit 1; }

while IFS='=' read -r key value; do
  case "$key" in
    USER_API_IMAGE) USER_IMAGE="$value" ;;
    USER_API_ACTIVE_COLOR) [ -z "$value" ] || { echo "blue/green routing is already active"; exit 0; } ;;
    ADMIN_API_IMAGE) ADMIN_IMAGE="$value" ;;
    ADMIN_API_ACTIVE_COLOR) [ -z "$value" ] || { echo "blue/green routing is already active"; exit 0; } ;;
    BATCH_API_IMAGE) BATCH_IMAGE="$value" ;;
    DEPLOYED_SHA) DEPLOYED_SHA="$value" ;;
  esac
done < "$STATE_FILE"

[ -n "$USER_IMAGE" ] && [ -n "$ADMIN_IMAGE" ] && [ -n "$BATCH_IMAGE" ] \
  || { echo "legacy deploy state does not contain all release images" >&2; exit 1; }
[ -f "$ENV_DIR/user-api.env" ] && [ -f "$ENV_DIR/admin-api.env" ] \
  || { echo "runtime env files are missing" >&2; exit 1; }

if ! command -v nginx >/dev/null 2>&1; then
  dnf install -y nginx
fi
mkdir -p "$ENV_DIR" "$SYSTEMD_DIR" "$NGINX_CONFIG_DIR"
chmod 700 "$ENV_DIR"

firebase_mount_option=""
if [ -s "$ENV_DIR/firebase-adminsdk.json" ]; then
  firebase_mount_option="-v /etc/rougether/firebase-adminsdk.json:/etc/rougether/firebase-adminsdk.json:ro"
fi

cat > "$ENV_DIR/user-api-blue.deploy.env" <<EOF
ROUGETHER_IMAGE=$USER_IMAGE
ROUGETHER_HOST_PORT=18080
EOF
cat > "$ENV_DIR/admin-api-blue.deploy.env" <<EOF
ROUGETHER_IMAGE=$ADMIN_IMAGE
ROUGETHER_HOST_PORT=18081
EOF
chmod 600 "$ENV_DIR/user-api-blue.deploy.env" "$ENV_DIR/admin-api-blue.deploy.env"

cat > "$SYSTEMD_DIR/rougether-user-api@.service" <<EOF
[Unit]
Description=Rougether user-api %i container
After=docker.service network-online.target
Requires=docker.service
Wants=network-online.target

[Service]
Restart=always
RestartSec=10
EnvironmentFile=/etc/rougether/user-api-%i.deploy.env
ExecStartPre=-/usr/bin/docker rm -f rougether-user-api-%i
ExecStart=/usr/bin/docker run --rm --name rougether-user-api-%i --memory 768m --memory-swap 768m --env JAVA_TOOL_OPTIONS=-Xmx512m --env-file /etc/rougether/user-api.env $firebase_mount_option -p 127.0.0.1:\${ROUGETHER_HOST_PORT}:8080 --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 \${ROUGETHER_IMAGE}
ExecStop=/usr/bin/docker stop --time 30 rougether-user-api-%i
TimeoutStopSec=45

[Install]
WantedBy=multi-user.target
EOF

cat > "$SYSTEMD_DIR/rougether-admin-api@.service" <<'EOF'
[Unit]
Description=Rougether admin-api %i container
After=docker.service network-online.target
Requires=docker.service
Wants=network-online.target

[Service]
Restart=always
RestartSec=10
EnvironmentFile=/etc/rougether/admin-api-%i.deploy.env
ExecStartPre=-/usr/bin/docker rm -f rougether-admin-api-%i
ExecStart=/usr/bin/docker run --rm --name rougether-admin-api-%i --network host --memory 640m --memory-swap 640m --env JAVA_TOOL_OPTIONS=-Xmx384m --env-file /etc/rougether/admin-api.env --env SERVER_PORT=${ROUGETHER_HOST_PORT} --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 ${ROUGETHER_IMAGE}
ExecStop=/usr/bin/docker stop --time 30 rougether-admin-api-%i
TimeoutStopSec=45

[Install]
WantedBy=multi-user.target
EOF
chmod 644 "$SYSTEMD_DIR/rougether-user-api@.service" "$SYSTEMD_DIR/rougether-admin-api@.service"
systemctl daemon-reload

wait_stable() {
  local name="$1" url="$2" deadline=$(( SECONDS + 180 )) successes=0
  while [ "$SECONDS" -lt "$deadline" ]; do
    if curl -fsS --connect-timeout 2 --max-time 5 "$url" >/dev/null; then
      successes=$(( successes + 1 ))
      [ "$successes" -ge 3 ] && { echo "$name is stable"; return 0; }
    else
      successes=0
    fi
    sleep 2
  done
  echo "$name did not become stable" >&2
  return 1
}

memory_preflight() {
  local required_kb="$1" available_kb
  available_kb="$(awk '/^MemAvailable:/ {print $2; exit}' /proc/meminfo)"
  [ "$available_kb" -ge "$required_kb" ] || {
    echo "insufficient MemAvailable: ${available_kb}KiB < ${required_kb}KiB" >&2
    return 1
  }
}

probe_candidate() {
  local service="$1" required_kb="$2" url="$3"
  memory_preflight "$required_kb"
  systemctl restart "rougether-$service@blue"
  if ! wait_stable "$service-blue" "$url"; then
    systemctl stop "rougether-$service@blue" || true
    return 1
  fi
  systemctl stop "rougether-$service@blue"
}

# Prove that each candidate can cold-start while the corresponding legacy API stays active.
probe_candidate user-api 1048576 http://127.0.0.1:18080/api/v1/health
probe_candidate admin-api 917504 http://127.0.0.1:18081/admin/health

if [ "$ACTIVATE" = false ]; then
  echo "blue/green preflight passed; fixed ports and legacy services were not changed"
  exit 0
fi

private_ip="$(ip -4 route get 1.1.1.1 | awk '{for (i = 1; i <= NF; i++) if ($i == "src") {print $(i + 1); exit}}')"
[ -n "$private_ip" ] || { echo "cannot determine private ENI IP" >&2; exit 1; }

render_nginx() {
  local user_port="$1" admin_port="$2" destination="$3"
  {
    echo '# Generated by bootstrap-blue-green-router.sh.'
    if [ -n "$user_port" ]; then
      cat <<EOF
upstream rougether_user_api { server 127.0.0.1:$user_port; keepalive 16; }
server {
  listen 8080;
  client_max_body_size 40m;
  location / {
    proxy_pass http://rougether_user_api;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host \$host;
    proxy_set_header X-Forwarded-For \$http_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto \$http_x_forwarded_proto;
    proxy_request_buffering off;
    proxy_read_timeout 120s;
  }
}
EOF
    fi
    if [ -n "$admin_port" ]; then
      cat <<EOF
upstream rougether_admin_api { server $private_ip:$admin_port; keepalive 8; }
server {
  listen 8081;
  client_max_body_size 12m;
  location / {
    proxy_pass http://rougether_admin_api;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host \$host;
    proxy_set_header X-Forwarded-For \$http_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto \$http_x_forwarded_proto;
    proxy_set_header X-Rougether-Admin-Origin \$http_x_rougether_admin_origin;
    proxy_set_header X-Rougether-Viewer-Ip \$http_x_rougether_viewer_ip;
    proxy_request_buffering off;
    proxy_read_timeout 120s;
  }
}
EOF
    fi
  } > "$destination"
}

apply_nginx() {
  local user_port="$1" admin_port="$2" temporary
  temporary="$(mktemp "$NGINX_CONFIG_DIR/.rougether.conf.XXXXXX")"
  render_nginx "$user_port" "$admin_port" "$temporary"
  chmod 644 "$temporary"
  mv -f "$temporary" "$NGINX_CONFIG_FILE"
  nginx -t
  if systemctl is-active --quiet nginx; then
    systemctl reload nginx
  else
    systemctl enable --now nginx
  fi
}

rollback_activation() {
  local exit_code="$?"
  trap - ERR
  echo "activation failed; restoring legacy fixed-port services" >&2
  if [ "$admin_legacy_stopped" = true ]; then
    render_nginx 18080 "" "$NGINX_CONFIG_FILE" || true
    nginx -t && systemctl reload nginx || true
    systemctl start rougether-admin-api || true
  fi
  systemctl stop rougether-admin-api@blue >/dev/null 2>&1 || true
  if [ "$user_legacy_stopped" = true ]; then
    rm -f "$NGINX_CONFIG_FILE"
    nginx -t && systemctl reload nginx || true
    systemctl start rougether-user-api || true
  fi
  systemctl stop rougether-user-api@blue >/dev/null 2>&1 || true
  exit "$exit_code"
}
trap rollback_activation ERR

memory_preflight 1048576
systemctl start rougether-user-api@blue
wait_stable user-api-blue http://127.0.0.1:18080/api/v1/health
systemctl stop rougether-user-api
user_legacy_stopped=true
apply_nginx 18080 ""
wait_stable user-api-proxy http://127.0.0.1:8080/api/v1/health

memory_preflight 917504
systemctl start rougether-admin-api@blue
wait_stable admin-api-blue http://127.0.0.1:18081/admin/health
systemctl stop rougether-admin-api
admin_legacy_stopped=true
apply_nginx 18080 18081
wait_stable admin-api-proxy http://127.0.0.1:8081/admin/health

systemctl enable rougether-user-api@blue rougether-admin-api@blue nginx
systemctl disable rougether-user-api rougether-admin-api >/dev/null 2>&1 || true

temporary_state="$(mktemp "$ENV_DIR/.deploy-state.env.XXXXXX")"
cat > "$temporary_state" <<EOF
USER_API_IMAGE=$USER_IMAGE
USER_API_ACTIVE_COLOR=blue
USER_API_ACTIVE_PORT=18080
ADMIN_API_IMAGE=$ADMIN_IMAGE
ADMIN_API_ACTIVE_COLOR=blue
ADMIN_API_ACTIVE_PORT=18081
BATCH_API_IMAGE=$BATCH_IMAGE
DEPLOYMENT_STATUS=ready
DEPLOYED_SHA=$DEPLOYED_SHA
TARGET_SHA=$DEPLOYED_SHA
DEPLOYED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
chmod 600 "$temporary_state"
mv -f "$temporary_state" "$STATE_FILE"
trap - ERR
echo "blue/green router activation completed"
