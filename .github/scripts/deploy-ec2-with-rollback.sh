#!/usr/bin/env bash
set -Eeuo pipefail

AWS_REGION="__AWS_REGION__"
REGISTRY="__REGISTRY__"
NEW_USER_IMAGE="__USER_IMAGE__"
NEW_ADMIN_IMAGE="__ADMIN_IMAGE__"
DEPLOYED_SHA="__DEPLOYED_SHA__"
FIREBASE_PARAMETER_NAME="__FIREBASE_PARAMETER_NAME__"

ENV_DIR="/etc/rougether"
SYSTEMD_DIR="${ROUGETHER_SYSTEMD_DIR:-/etc/systemd/system}"
STATE_FILE="$ENV_DIR/deploy-state.env"
USER_DEPLOY_ENV="$ENV_DIR/user-api.deploy.env"
ADMIN_DEPLOY_ENV="$ENV_DIR/admin-api.deploy.env"
USER_RUNTIME_ENV="$ENV_DIR/user-api.env"
FIREBASE_CREDENTIALS_FILE="$ENV_DIR/firebase-adminsdk.json"

umask 077

rollback_user_image=""
rollback_admin_image=""
firebase_credentials_backup=""
firebase_credentials_replaced=false

firebase_credentials_valid() {
  local credentials_file="$1"

  [ -f "$credentials_file" ] || return 1

  python3 - "$credentials_file" <<'PY'
import json
import sys

try:
    with open(sys.argv[1], encoding="utf-8") as credentials_file:
        credentials = json.load(credentials_file)
except (OSError, json.JSONDecodeError):
    raise SystemExit(1)

required = ("project_id", "private_key", "client_email")
if credentials.get("type") != "service_account" or any(not credentials.get(key) for key in required):
    raise SystemExit(1)
PY
}

backup_firebase_credentials() {
  if firebase_credentials_valid "$FIREBASE_CREDENTIALS_FILE"; then
    firebase_credentials_backup="$(mktemp "$ENV_DIR/.firebase-adminsdk.rollback.XXXXXX")"
    cp -p "$FIREBASE_CREDENTIALS_FILE" "$firebase_credentials_backup"
  fi
}

cleanup_firebase_credentials_backup() {
  if [ "$firebase_credentials_replaced" = true ]; then
    if [ -n "$firebase_credentials_backup" ]; then
      echo "Firebase credential rollback is incomplete; keeping backup at $firebase_credentials_backup" >&2
    else
      echo "Firebase credential rollback is incomplete; no previous credential backup exists" >&2
    fi
    return 0
  fi

  if [ -n "$firebase_credentials_backup" ]; then
    rm -f "$firebase_credentials_backup"
    firebase_credentials_backup=""
  fi
}

restore_firebase_credentials() {
  [ "$firebase_credentials_replaced" = true ] || return 0

  if [ -n "$firebase_credentials_backup" ] && [ -f "$firebase_credentials_backup" ]; then
    mv -f "$firebase_credentials_backup" "$FIREBASE_CREDENTIALS_FILE" || return 1
    firebase_credentials_backup=""
  else
    rm -f "$FIREBASE_CREDENTIALS_FILE" || return 1
  fi

  firebase_credentials_replaced=false
}

refresh_firebase_credentials() {
  local temporary_file

  mkdir -p "$ENV_DIR"
  chmod 700 "$ENV_DIR"
  temporary_file="$(mktemp "$ENV_DIR/.firebase-adminsdk.json.XXXXXX")"

  if ! aws ssm get-parameter --name "$FIREBASE_PARAMETER_NAME" --with-decryption \
    --query 'Parameter.Value' --output text --region "$AWS_REGION" > "$temporary_file"; then
    rm -f "$temporary_file"
    echo "Firebase credentials unavailable in SSM; keeping the current credentials or FCM stub" >&2
    return 0
  fi

  if ! firebase_credentials_valid "$temporary_file"; then
    rm -f "$temporary_file"
    echo "Invalid Firebase credentials in SSM; keeping the current credentials or FCM stub" >&2
    return 0
  fi

  chmod 600 "$temporary_file"
  chown root:root "$temporary_file"
  mv -f "$temporary_file" "$FIREBASE_CREDENTIALS_FILE"
  firebase_credentials_replaced=true
  echo "Firebase credentials refreshed from SSM"
}

ensure_user_runtime_env() {
  local temporary_env

  if [ ! -f "$USER_RUNTIME_ENV" ]; then
    echo "missing user-api runtime env: $USER_RUNTIME_ENV" >&2
    return 1
  fi

  temporary_env="$(mktemp "$ENV_DIR/.user-api.env.XXXXXX")"
  if ! awk '!/^FIREBASE_CREDENTIALS_PATH=/' "$USER_RUNTIME_ENV" > "$temporary_env"; then
    rm -f "$temporary_env"
    return 1
  fi

  if firebase_credentials_valid "$FIREBASE_CREDENTIALS_FILE"; then
    printf '\nFIREBASE_CREDENTIALS_PATH=/etc/rougether/firebase-adminsdk.json\n' >> "$temporary_env"
  fi

  chmod 600 "$temporary_env" || return 1
  mv -f "$temporary_env" "$USER_RUNTIME_ENV" || return 1
}

wait_health() {
  local name="$1"
  local url="$2"

  # wall-clock 데드라인 — 횟수 기반은 실패당 최대 8초(curl 5초 + sleep 3초)씩 늘어나
  # 워크플로의 SSM 감시 한도(120×10초=1,200초)를 넘길 수 있다. 총 대기를 시계 기준으로 못박아
  # 최악(배포 2회 + 롤백 2회 대기)에도 4×240=960초로 감시 한도 안에 들어오게 한다.
  # 240초는 실측 부팅(~30초)의 8배 여유. 테스트에서 단축할 수 있게 env 로만 조절 가능.
  local timeout_seconds="${ROUGETHER_HEALTH_TIMEOUT_SECONDS:-240}"
  local deadline=$(( SECONDS + timeout_seconds ))
  local attempt=0

  while [ "$SECONDS" -lt "$deadline" ]; do
    attempt=$(( attempt + 1 ))
    if curl -fsS --connect-timeout 2 --max-time 5 "$url"; then
      echo "$name health check passed"
      return 0
    fi

    echo "waiting for $name health check (attempt $attempt, $(( deadline - SECONDS ))s left)"
    sleep 3
  done

  echo "$name health check failed" >&2
  return 1
}

write_units() {
  local user_image="$1"
  local admin_image="$2"
  local firebase_mount_option=""
  local user_service_file="$SYSTEMD_DIR/rougether-user-api.service"
  local admin_service_file="$SYSTEMD_DIR/rougether-admin-api.service"

  if firebase_credentials_valid "$FIREBASE_CREDENTIALS_FILE"; then
    firebase_mount_option="-v /etc/rougether/firebase-adminsdk.json:/etc/rougether/firebase-adminsdk.json:ro"
  fi

  mkdir -p "$ENV_DIR"
  mkdir -p "$SYSTEMD_DIR"
  chmod 700 "$ENV_DIR"

  cat > "$USER_DEPLOY_ENV" <<EOF
ROUGETHER_USER_API_IMAGE=$user_image
EOF

  cat > "$ADMIN_DEPLOY_ENV" <<EOF
ROUGETHER_ADMIN_API_IMAGE=$admin_image
EOF

  chmod 600 "$USER_DEPLOY_ENV" "$ADMIN_DEPLOY_ENV"

  cat > "$user_service_file" <<EOF
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
ExecStart=/usr/bin/docker run --rm --name rougether-user-api --env-file /etc/rougether/user-api.env $firebase_mount_option -p 8080:8080 --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 \${ROUGETHER_USER_API_IMAGE}
ExecStop=/usr/bin/docker stop rougether-user-api

[Install]
WantedBy=multi-user.target
EOF

  cat > "$admin_service_file" <<'EOF'
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
    while IFS='=' read -r key value; do
      case "$key" in
        USER_API_IMAGE)
          rollback_user_image="$value"
          ;;
        ADMIN_API_IMAGE)
          rollback_admin_image="$value"
          ;;
      esac
    done < "$STATE_FILE"
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

  if ! restore_firebase_credentials; then
    echo "Firebase credential restore failed; continuing image rollback" >&2
  fi

  if ! ensure_user_runtime_env; then
    echo "Firebase runtime env restore failed; continuing image rollback" >&2
  fi

  if [ -z "$rollback_user_image" ] || [ -z "$rollback_admin_image" ]; then
    cleanup_firebase_credentials_backup
    echo "rollback skipped: previous images are not available" >&2
    exit "$exit_code"
  fi

  write_units "$rollback_user_image" "$rollback_admin_image"

  # 두 서비스를 병렬 기동 후 순서대로 health 확인 — 순차 기동(user healthy 후 admin 시작)이면
  # 부팅 시간이 직렬로 더해진다. 두 컨테이너는 포트·상태가 독립이라 동시 기동에 제약이 없다.
  # health 실패를 명시적으로 집계한다 — 트랩/errexit 문맥에 따라 중단 여부가 달라지지 않게 하고,
  # 실패해도 백업 정리·원래 실패 코드 전파는 항상 수행한다.
  systemctl restart rougether-user-api rougether-admin-api
  local rollback_health_ok=true
  wait_health user-api http://127.0.0.1:8080/api/v1/health || rollback_health_ok=false
  wait_health admin-api http://127.0.0.1:8081/admin/health || rollback_health_ok=false

  cleanup_firebase_credentials_backup
  if [ "$rollback_health_ok" = true ]; then
    echo "rollback completed"
  else
    echo "rollback finished but health checks failed" >&2
  fi
  exit "$exit_code"
}

capture_rollback_images
backup_firebase_credentials
refresh_firebase_credentials
if ! ensure_user_runtime_env; then
  if ! restore_firebase_credentials; then
    echo "Firebase credential restore failed; preserving backup before exit" >&2
  fi
  cleanup_firebase_credentials_backup
  exit 1
fi

trap rollback ERR

aws ecr get-login-password --region "$AWS_REGION" | docker login "$REGISTRY" --username AWS --password-stdin
docker pull "$NEW_USER_IMAGE"
docker pull "$NEW_ADMIN_IMAGE"

write_units "$NEW_USER_IMAGE" "$NEW_ADMIN_IMAGE"

# 병렬 기동 후 순서대로 health 확인 (rollback 경로와 동일 원칙) —
# user-api health 를 기다리는 동안 admin-api 가 함께 부팅되므로 두 번째 대기는 짧다
systemctl restart rougether-user-api rougether-admin-api
wait_health user-api http://127.0.0.1:8080/api/v1/health
wait_health admin-api http://127.0.0.1:8081/admin/health

cat > "$STATE_FILE" <<EOF
USER_API_IMAGE=$NEW_USER_IMAGE
ADMIN_API_IMAGE=$NEW_ADMIN_IMAGE
DEPLOYED_SHA=$DEPLOYED_SHA
DEPLOYED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

chmod 600 "$STATE_FILE"
trap - ERR
firebase_credentials_replaced=false
cleanup_firebase_credentials_backup
docker ps
