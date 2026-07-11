#!/usr/bin/env bash
set -Eeuo pipefail

AWS_REGION="__AWS_REGION__"
REGISTRY="__REGISTRY__"
NEW_USER_IMAGE="__USER_IMAGE__"
NEW_ADMIN_IMAGE="__ADMIN_IMAGE__"
DEPLOYED_SHA="__DEPLOYED_SHA__"

ENV_DIR="/etc/rougether"
STATE_FILE="$ENV_DIR/deploy-state.env"
USER_DEPLOY_ENV="$ENV_DIR/user-api.deploy.env"
ADMIN_DEPLOY_ENV="$ENV_DIR/admin-api.deploy.env"

rollback_user_image=""
rollback_admin_image=""

wait_health() {
  local name="$1"
  local url="$2"

  for i in $(seq 1 80); do
    if curl -fsS "$url"; then
      echo "$name health check passed"
      return 0
    fi

    echo "waiting for $name health check ($i/80)"
    sleep 10
  done

  echo "$name health check failed" >&2
  return 1
}

write_units() {
  local user_image="$1"
  local admin_image="$2"

  mkdir -p "$ENV_DIR"
  chmod 700 "$ENV_DIR"

  # firebase 서비스 계정 키는 코드로 배포되지 않고 서버에 별도로 올려둠(secret).
  # 파일이 없으면 아래 docker -v 마운트가 그 경로에 빈 디렉토리를 만들어버리므로
  # 빈 placeholder를 미리 만들어 마운트 소스가 항상 "파일"이 되게 함.
  if [ ! -f "$ENV_DIR/firebase-adminsdk.json" ]; then
    touch "$ENV_DIR/firebase-adminsdk.json"
    chmod 600 "$ENV_DIR/firebase-adminsdk.json"
  fi

  cat > "$USER_DEPLOY_ENV" <<EOF
ROUGETHER_USER_API_IMAGE=$user_image
EOF

  cat > "$ADMIN_DEPLOY_ENV" <<EOF
ROUGETHER_ADMIN_API_IMAGE=$admin_image
EOF

  chmod 600 "$USER_DEPLOY_ENV" "$ADMIN_DEPLOY_ENV"

  cat > /etc/systemd/system/rougether-user-api.service <<'EOF'
[Unit]
Description=Rougether user-api container
After=docker.service network-online.target
Requires=docker.service
Wants=network-online.target

[Service]
Restart=always
RestartSec=10
EnvironmentFile=/etc/rougether/user-api.deploy.env
ExecStartPre=-/usr/bin/docker rm -f rougether-user-api
ExecStart=/usr/bin/docker run --rm --name rougether-user-api --env-file /etc/rougether/user-api.env -v /etc/rougether/firebase-adminsdk.json:/etc/rougether/firebase-adminsdk.json:ro -p 8080:8080 --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 ${ROUGETHER_USER_API_IMAGE}
ExecStop=/usr/bin/docker stop rougether-user-api

[Install]
WantedBy=multi-user.target
EOF

  cat > /etc/systemd/system/rougether-admin-api.service <<'EOF'
[Unit]
Description=Rougether admin-api container
After=docker.service network-online.target rougether-user-api.service
Requires=docker.service
Wants=network-online.target

[Service]
Restart=always
RestartSec=10
EnvironmentFile=/etc/rougether/admin-api.deploy.env
ExecStartPre=-/usr/bin/docker rm -f rougether-admin-api
ExecStart=/usr/bin/docker run --rm --name rougether-admin-api --env-file /etc/rougether/admin-api.env -p 8081:8081 --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 ${ROUGETHER_ADMIN_API_IMAGE}
ExecStop=/usr/bin/docker stop rougether-admin-api

[Install]
WantedBy=multi-user.target
EOF

  systemctl daemon-reload
  systemctl enable rougether-user-api rougether-admin-api
}

capture_rollback_images() {
  if [ -f "$STATE_FILE" ]; then
    # shellcheck disable=SC1090
    . "$STATE_FILE"
    rollback_user_image="${USER_API_IMAGE:-}"
    rollback_admin_image="${ADMIN_API_IMAGE:-}"
  fi

  if [ -z "$rollback_user_image" ]; then
    local current_user_image_id
    current_user_image_id="$(docker inspect --format '{{.Image}}' rougether-user-api 2>/dev/null || true)"

    if [ -n "$current_user_image_id" ]; then
      rollback_user_image="$REGISTRY/rougether-dev/user-api:rollback-$DEPLOYED_SHA"
      docker tag "$current_user_image_id" "$rollback_user_image"
    fi
  fi

  if [ -z "$rollback_admin_image" ]; then
    local current_admin_image_id
    current_admin_image_id="$(docker inspect --format '{{.Image}}' rougether-admin-api 2>/dev/null || true)"

    if [ -n "$current_admin_image_id" ]; then
      rollback_admin_image="$REGISTRY/rougether-dev/admin-api:rollback-$DEPLOYED_SHA"
      docker tag "$current_admin_image_id" "$rollback_admin_image"
    fi
  fi
}

rollback() {
  local exit_code="$?"
  trap - ERR

  echo "deploy failed; attempting rollback"

  if [ -z "$rollback_user_image" ] || [ -z "$rollback_admin_image" ]; then
    echo "rollback skipped: previous images are not available" >&2
    exit "$exit_code"
  fi

  write_units "$rollback_user_image" "$rollback_admin_image"

  systemctl restart rougether-user-api
  wait_health user-api http://127.0.0.1:8080/api/v1/health

  systemctl restart rougether-admin-api
  wait_health admin-api http://127.0.0.1:8081/admin/health

  echo "rollback completed"
  exit "$exit_code"
}

trap rollback ERR

capture_rollback_images

aws ecr get-login-password --region "$AWS_REGION" | docker login "$REGISTRY" --username AWS --password-stdin
docker pull "$NEW_USER_IMAGE"
docker pull "$NEW_ADMIN_IMAGE"

write_units "$NEW_USER_IMAGE" "$NEW_ADMIN_IMAGE"

systemctl restart rougether-user-api
wait_health user-api http://127.0.0.1:8080/api/v1/health

systemctl restart rougether-admin-api
wait_health admin-api http://127.0.0.1:8081/admin/health

cat > "$STATE_FILE" <<EOF
USER_API_IMAGE=$NEW_USER_IMAGE
ADMIN_API_IMAGE=$NEW_ADMIN_IMAGE
DEPLOYED_SHA=$DEPLOYED_SHA
DEPLOYED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

chmod 600 "$STATE_FILE"
docker ps
