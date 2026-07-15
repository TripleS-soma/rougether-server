#!/usr/bin/env bash
set -Eeuo pipefail

AWS_REGION="__AWS_REGION__"
REGISTRY="__REGISTRY__"
NEW_USER_IMAGE="__USER_IMAGE__"
NEW_ADMIN_IMAGE="__ADMIN_IMAGE__"
NEW_BATCH_IMAGE="__BATCH_IMAGE__"
DEPLOYED_SHA="__DEPLOYED_SHA__"
FIREBASE_PARAMETER_NAME="__FIREBASE_PARAMETER_NAME__"

ENV_DIR="/etc/rougether"
SYSTEMD_DIR="${ROUGETHER_SYSTEMD_DIR:-/etc/systemd/system}"
STATE_FILE="$ENV_DIR/deploy-state.env"
USER_DEPLOY_ENV="$ENV_DIR/user-api.deploy.env"
ADMIN_DEPLOY_ENV="$ENV_DIR/admin-api.deploy.env"
BATCH_DEPLOY_ENV="$ENV_DIR/batch.deploy.env"
USER_RUNTIME_ENV="$ENV_DIR/user-api.env"
ADMIN_RUNTIME_ENV="$ENV_DIR/admin-api.env"
BATCH_RUNTIME_ENV="$ENV_DIR/batch.env"
FIREBASE_CREDENTIALS_FILE="$ENV_DIR/firebase-adminsdk.json"

umask 077

rollback_user_image=""
rollback_admin_image=""
rollback_batch_image=""
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

bootstrap_batch_runtime_env() {
  # batch 유닛은 /etc/rougether/batch.env 를 요구한다. 이 파일은 user-data 부트스트랩에서만
  # 생성되는데, aws_instance.app 이 user_data 변경을 무시하므로 batch 도입 전에 뜬 기존 인스턴스에는
  # 없다. 같은 DB 접속을 쓰는 user-api.env(없으면 admin-api.env)에서 DB_* 를 복사해 한 번 생성한다.
  # (firebase 경로는 아래 ensure_batch_runtime_env 가 자격증명 유효성에 맞춰 매 배포 재조정한다.)
  [ -f "$BATCH_RUNTIME_ENV" ] && return 0

  local source_env="$USER_RUNTIME_ENV"
  if [ ! -f "$source_env" ]; then
    source_env="$ADMIN_RUNTIME_ENV"
  fi
  if [ ! -f "$source_env" ]; then
    echo "cannot bootstrap batch.env: no source runtime env found" >&2
    return 1
  fi

  local db_lines
  db_lines="$(grep -E '^(DB_URL|DB_USERNAME|DB_PASSWORD)=' "$source_env" || true)"
  if [ -z "$db_lines" ]; then
    echo "cannot bootstrap batch.env: DB settings missing in $source_env" >&2
    return 1
  fi

  mkdir -p "$ENV_DIR"
  chmod 700 "$ENV_DIR"

  local temporary_env
  temporary_env="$(mktemp "$ENV_DIR/.batch.env.XXXXXX")"
  {
    echo "SPRING_PROFILES_ACTIVE=mysql"
    echo "SERVER_PORT=8082"
    printf '%s\n' "$db_lines"
  } > "$temporary_env"

  chmod 600 "$temporary_env"
  mv -f "$temporary_env" "$BATCH_RUNTIME_ENV"
  echo "bootstrapped $BATCH_RUNTIME_ENV from $source_env"
}

ensure_batch_runtime_env() {
  # 없으면 먼저 부트스트랩한 뒤, firebase 자격증명 유효성에 맞춰 FIREBASE_CREDENTIALS_PATH 를
  # 매 배포 재조정한다(user-api 의 ensure_user_runtime_env 와 동일한 규칙).
  if ! bootstrap_batch_runtime_env; then
    return 1
  fi

  local temporary_env
  temporary_env="$(mktemp "$ENV_DIR/.batch.env.XXXXXX")"
  if ! awk '!/^FIREBASE_CREDENTIALS_PATH=/' "$BATCH_RUNTIME_ENV" > "$temporary_env"; then
    rm -f "$temporary_env"
    return 1
  fi

  if firebase_credentials_valid "$FIREBASE_CREDENTIALS_FILE"; then
    printf '\nFIREBASE_CREDENTIALS_PATH=/etc/rougether/firebase-adminsdk.json\n' >> "$temporary_env"
  fi

  chmod 600 "$temporary_env" || return 1
  mv -f "$temporary_env" "$BATCH_RUNTIME_ENV" || return 1
}

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
  local batch_image="$3"
  local firebase_mount_option=""
  local user_service_file="$SYSTEMD_DIR/rougether-user-api.service"
  local admin_service_file="$SYSTEMD_DIR/rougether-admin-api.service"
  local batch_service_file="$SYSTEMD_DIR/rougether-batch.service"

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

  cat > "$BATCH_DEPLOY_ENV" <<EOF
ROUGETHER_BATCH_IMAGE=$batch_image
EOF

  chmod 600 "$USER_DEPLOY_ENV" "$ADMIN_DEPLOY_ENV" "$BATCH_DEPLOY_ENV"

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

  # batch 는 user-api/admin-api 에 의존하지 않는 독립 유닛이다(리마인드 발송을 다른 배포가 끊지 않도록).
  # 외부 접근 없이 localhost:8082 헬스체크만 노출한다. firebase 자격증명이 있으면 user-api 와
  # 동일하게 마운트해 실제 FCM 을 발송한다(<<EOF 로 $firebase_mount_option 을 배포 시점에 굽는다).
  cat > "$batch_service_file" <<EOF
[Unit]
Description=Rougether batch container
After=docker.service network-online.target
Requires=docker.service
Wants=network-online.target

[Service]
Restart=always
RestartSec=10
EnvironmentFile=/etc/rougether/batch.deploy.env
ExecStartPre=-/usr/bin/docker rm -f rougether-batch
ExecStart=/usr/bin/docker run --rm --name rougether-batch --env-file /etc/rougether/batch.env $firebase_mount_option -p 127.0.0.1:8082:8082 --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 \${ROUGETHER_BATCH_IMAGE}
ExecStop=/usr/bin/docker stop rougether-batch

[Install]
WantedBy=multi-user.target
EOF

  systemctl daemon-reload
  systemctl enable rougether-user-api rougether-admin-api rougether-batch
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
        BATCH_API_IMAGE)
          rollback_batch_image="$value"
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

  if [ -z "$rollback_batch_image" ]; then
    local current_batch_image_id
    current_batch_image_id="$(docker inspect --format '{{.Image}}' rougether-batch 2>/dev/null || true)"

    if [ -n "$current_batch_image_id" ]; then
      rollback_batch_image="$REGISTRY/rougether-dev/batch:rollback-$DEPLOYED_SHA"
      docker tag "$current_batch_image_id" "$rollback_batch_image"
    fi
  fi
}

rollback_batch() {
  # batch 는 독립 유닛이라 롤백 처리가 user-api/admin-api 복구를 가리지 않게 best-effort 로 한다.
  if [ -n "$rollback_batch_image" ]; then
    # 되돌릴 이전 이미지가 있으면 그 이미지로 재기동한다. restart/health 어느 쪽이 실패해도
    # set -e 로 여기서 죽지 않게 감싸 원래 exit code 와 cleanup 을 보존한다.
    if ! ensure_batch_runtime_env \
      || ! systemctl restart rougether-batch \
      || ! wait_health batch http://127.0.0.1:8082/actuator/health; then
      echo "batch rollback failed; user-api/admin-api rollback is unaffected" >&2
    fi
  else
    # 최초 도입 배포라 되돌릴 이전 이미지가 없다. 방금 기동한 실패 이미지가 Restart=always 로
    # 계속 스케줄 작업을 돌거나 crash-loop 하지 않도록 명시적으로 정지·비활성화한다.
    echo "no previous batch image to roll back to; stopping the failed new batch" >&2
    systemctl stop rougether-batch || true
    systemctl disable rougether-batch || true
    docker rm -f rougether-batch >/dev/null 2>&1 || true
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

  # batch 는 최초 도입 배포 시 이전 이미지가 없을 수 있다 — 없으면 새 이미지로 유닛만 써 두고 재기동은 건너뛴다.
  write_units "$rollback_user_image" "$rollback_admin_image" "${rollback_batch_image:-$NEW_BATCH_IMAGE}"

  systemctl restart rougether-user-api
  wait_health user-api http://127.0.0.1:8080/api/v1/health

  systemctl restart rougether-admin-api
  wait_health admin-api http://127.0.0.1:8081/admin/health

  rollback_batch

  cleanup_firebase_credentials_backup
  echo "rollback completed"
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
docker pull "$NEW_BATCH_IMAGE"

write_units "$NEW_USER_IMAGE" "$NEW_ADMIN_IMAGE" "$NEW_BATCH_IMAGE"

systemctl restart rougether-user-api
wait_health user-api http://127.0.0.1:8080/api/v1/health

systemctl restart rougether-admin-api
wait_health admin-api http://127.0.0.1:8081/admin/health

ensure_batch_runtime_env
systemctl restart rougether-batch
wait_health batch http://127.0.0.1:8082/actuator/health

cat > "$STATE_FILE" <<EOF
USER_API_IMAGE=$NEW_USER_IMAGE
ADMIN_API_IMAGE=$NEW_ADMIN_IMAGE
BATCH_API_IMAGE=$NEW_BATCH_IMAGE
DEPLOYED_SHA=$DEPLOYED_SHA
DEPLOYED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

chmod 600 "$STATE_FILE"
trap - ERR
firebase_credentials_replaced=false
cleanup_firebase_credentials_backup
docker ps
