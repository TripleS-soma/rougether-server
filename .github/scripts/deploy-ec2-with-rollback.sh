#!/usr/bin/env bash
set -Eeuo pipefail

AWS_REGION="__AWS_REGION__"
REGISTRY="__REGISTRY__"
NEW_USER_IMAGE="__USER_IMAGE__"
NEW_ADMIN_IMAGE="__ADMIN_IMAGE__"
NEW_BATCH_IMAGE="__BATCH_IMAGE__"
DEPLOYED_SHA="__DEPLOYED_SHA__"
FIREBASE_PARAMETER_NAME="__FIREBASE_PARAMETER_NAME__"
ADMIN_ORIGIN_SECRET_PARAMETER_NAME="__ADMIN_ORIGIN_SECRET_PARAMETER_NAME__"
WEBEX_BOT_TOKEN_PARAMETER_NAME="__WEBEX_BOT_TOKEN_PARAMETER_NAME__"
KAKAO_ADMIN_KEY_PARAMETER_NAME="__KAKAO_ADMIN_KEY_PARAMETER_NAME__"
APPLE_TEAM_ID_PARAMETER_NAME="__APPLE_TEAM_ID_PARAMETER_NAME__"
APPLE_KEY_ID_PARAMETER_NAME="__APPLE_KEY_ID_PARAMETER_NAME__"
APPLE_PRIVATE_KEY_PARAMETER_NAME="__APPLE_PRIVATE_KEY_PARAMETER_NAME__"
APPLE_REFRESH_TOKEN_ENC_KEY_PARAMETER_NAME="__APPLE_REFRESH_TOKEN_ENC_KEY_PARAMETER_NAME__"
LLM_API_KEY_PARAMETER_NAME="__LLM_API_KEY_PARAMETER_NAME__"
WEBEX_ROOM_ID="__WEBEX_ROOM_ID__"
ENVIRONMENT="__ENVIRONMENT__"

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

escape_multiline_env_value() {
  python3 -c '
import sys

value = sys.stdin.read()
value = value.replace("\r\n", "\n").replace("\r", "\n")
sys.stdout.write(value.replace("\n", r"\n"))
'
}

single_line_secret_valid() {
  local value_file="$1"
  local maximum_length="$2"

  python3 - "$value_file" "$maximum_length" <<'PY'
import sys

with open(sys.argv[1], encoding="utf-8") as value_file:
    value = value_file.read().strip()
valid = bool(value) and len(value) <= int(sys.argv[2]) and not any(character.isspace() for character in value)
raise SystemExit(0 if valid else 1)
PY
}

multiline_secret_valid() {
  local value_file="$1"
  python3 - "$value_file" <<'PY'
import sys

with open(sys.argv[1], encoding="utf-8") as value_file:
    value = value_file.read()
valid = bool(value.strip()) and len(value) <= 16384 and "\0" not in value
raise SystemExit(0 if valid else 1)
PY
}

refresh_admin_origin_secret_env() {
  local secret_file temporary_env

  if [ ! -f "$ADMIN_RUNTIME_ENV" ]; then
    echo "missing admin-api runtime env: $ADMIN_RUNTIME_ENV" >&2
    return 1
  fi

  secret_file="$(mktemp "$ENV_DIR/.admin-origin-secret.XXXXXX")"
  if ! aws ssm get-parameter --name "$ADMIN_ORIGIN_SECRET_PARAMETER_NAME" --with-decryption \
      --query 'Parameter.Value' --output text --region "$AWS_REGION" > "$secret_file" \
      || ! single_line_secret_valid "$secret_file" 256; then
    rm -f "$secret_file"
    echo "Admin origin secret is unavailable or invalid" >&2
    return 1
  fi

  temporary_env="$(mktemp "$ENV_DIR/.admin-api.env.XXXXXX")"
  awk '!/^ADMIN_ORIGIN_SECRET=/' "$ADMIN_RUNTIME_ENV" > "$temporary_env"
  printf '\nADMIN_ORIGIN_SECRET=%s\n' "$(tr -d '\r\n' < "$secret_file")" >> "$temporary_env"
  chmod 600 "$temporary_env"
  mv -f "$temporary_env" "$ADMIN_RUNTIME_ENV"
  rm -f "$secret_file"
}

fetch_secret_parameter() {
  local parameter_name="$1"
  local destination="$2"
  aws ssm get-parameter --name "$parameter_name" --with-decryption \
    --query 'Parameter.Value' --output text --region "$AWS_REGION" > "$destination"
}

refresh_social_auth_env() {
  local kakao_file apple_team_file apple_key_file apple_private_file apple_enc_file temporary_env
  local replace_kakao=false replace_apple_team=false replace_apple_key=false
  local replace_apple_private=false replace_apple_enc=false

  if [ ! -f "$USER_RUNTIME_ENV" ]; then
    echo "missing user-api runtime env: $USER_RUNTIME_ENV" >&2
    return 1
  fi

  kakao_file="$(mktemp "$ENV_DIR/.kakao-admin-key.XXXXXX")"
  apple_team_file="$(mktemp "$ENV_DIR/.apple-team-id.XXXXXX")"
  apple_key_file="$(mktemp "$ENV_DIR/.apple-key-id.XXXXXX")"
  apple_private_file="$(mktemp "$ENV_DIR/.apple-private-key.XXXXXX")"
  apple_enc_file="$(mktemp "$ENV_DIR/.apple-refresh-token-key.XXXXXX")"

  if fetch_secret_parameter "$KAKAO_ADMIN_KEY_PARAMETER_NAME" "$kakao_file" \
      && single_line_secret_valid "$kakao_file" 2048; then
    replace_kakao=true
  else
    echo "Kakao admin key unavailable or invalid; keeping the current runtime value" >&2
  fi
  if fetch_secret_parameter "$APPLE_TEAM_ID_PARAMETER_NAME" "$apple_team_file" \
      && single_line_secret_valid "$apple_team_file" 128; then
    replace_apple_team=true
  else
    echo "Apple team ID unavailable or invalid; keeping the current runtime value" >&2
  fi
  if fetch_secret_parameter "$APPLE_KEY_ID_PARAMETER_NAME" "$apple_key_file" \
      && single_line_secret_valid "$apple_key_file" 128; then
    replace_apple_key=true
  else
    echo "Apple key ID unavailable or invalid; keeping the current runtime value" >&2
  fi
  if fetch_secret_parameter "$APPLE_PRIVATE_KEY_PARAMETER_NAME" "$apple_private_file" \
      && multiline_secret_valid "$apple_private_file"; then
    replace_apple_private=true
  else
    echo "Apple private key unavailable or invalid; keeping the current runtime value" >&2
  fi
  if fetch_secret_parameter "$APPLE_REFRESH_TOKEN_ENC_KEY_PARAMETER_NAME" "$apple_enc_file" \
      && single_line_secret_valid "$apple_enc_file" 4096; then
    replace_apple_enc=true
  else
    echo "Apple refresh-token key unavailable or invalid; keeping the current runtime value" >&2
  fi

  temporary_env="$(mktemp "$ENV_DIR/.user-api.env.XXXXXX")"
  awk \
    -v replace_kakao="$replace_kakao" \
    -v replace_apple_team="$replace_apple_team" \
    -v replace_apple_key="$replace_apple_key" \
    -v replace_apple_private="$replace_apple_private" \
    -v replace_apple_enc="$replace_apple_enc" '
      /^ASSET_S3_PURGE_VERSIONS=/ { next }
      replace_kakao == "true" && /^KAKAO_ADMIN_KEY=/ { next }
      replace_apple_team == "true" && /^APPLE_TEAM_ID=/ { next }
      replace_apple_key == "true" && /^APPLE_KEY_ID=/ { next }
      replace_apple_private == "true" && /^APPLE_PRIVATE_KEY=/ { next }
      replace_apple_enc == "true" && /^APPLE_REFRESH_TOKEN_ENC_KEY=/ { next }
      { print }
  ' "$USER_RUNTIME_ENV" > "$temporary_env"

  printf '\nASSET_S3_PURGE_VERSIONS=true\n' >> "$temporary_env"
  if [ "$replace_kakao" = true ]; then
    printf 'KAKAO_ADMIN_KEY=%s\n' "$(tr -d '\r\n' < "$kakao_file")" >> "$temporary_env"
  fi
  if [ "$replace_apple_team" = true ]; then
    printf 'APPLE_TEAM_ID=%s\n' "$(tr -d '\r\n' < "$apple_team_file")" >> "$temporary_env"
  fi
  if [ "$replace_apple_key" = true ]; then
    printf 'APPLE_KEY_ID=%s\n' "$(tr -d '\r\n' < "$apple_key_file")" >> "$temporary_env"
  fi
  if [ "$replace_apple_private" = true ]; then
    printf 'APPLE_PRIVATE_KEY=%s\n' "$(escape_multiline_env_value < "$apple_private_file")" >> "$temporary_env"
  fi
  if [ "$replace_apple_enc" = true ]; then
    printf 'APPLE_REFRESH_TOKEN_ENC_KEY=%s\n' "$(tr -d '\r\n' < "$apple_enc_file")" >> "$temporary_env"
  fi

  chmod 600 "$temporary_env"
  mv -f "$temporary_env" "$USER_RUNTIME_ENV"
  rm -f "$kakao_file" "$apple_team_file" "$apple_key_file" "$apple_private_file" "$apple_enc_file"
}

webex_bot_token_valid() {
  local token_file="$1"

  python3 - "$token_file" <<'PY'
import sys

with open(sys.argv[1], encoding="utf-8") as token_file:
    value = token_file.read().strip()

valid = bool(value) and len(value) <= 2048 and not any(character.isspace() for character in value)
raise SystemExit(0 if valid else 1)
PY
}

webex_room_id_valid() {
  [ -n "$WEBEX_ROOM_ID" ] \
    && [ "${#WEBEX_ROOM_ID}" -le 1024 ] \
    && [[ "$WEBEX_ROOM_ID" != *[[:space:]]* ]]
}

refresh_webex_alert_env() {
  local token_file temporary_env replace_token=false replace_room=false

  if [ ! -f "$USER_RUNTIME_ENV" ]; then
    echo "missing user-api runtime env: $USER_RUNTIME_ENV" >&2
    return 1
  fi

  token_file="$(mktemp "$ENV_DIR/.webex-bot-token.XXXXXX")"
  if aws ssm get-parameter --name "$WEBEX_BOT_TOKEN_PARAMETER_NAME" --with-decryption \
      --query 'Parameter.Value' --output text --region "$AWS_REGION" > "$token_file" \
      && webex_bot_token_valid "$token_file"; then
    replace_token=true
  else
    echo "Webex bot token unavailable or invalid; keeping the current token" >&2
  fi

  if webex_room_id_valid; then
    replace_room=true
  else
    echo "Webex room ID unavailable or invalid; keeping the current room ID" >&2
  fi

  temporary_env="$(mktemp "$ENV_DIR/.user-api.env.XXXXXX")"
  awk -v replace_token="$replace_token" -v replace_room="$replace_room" '
    /^OPERATIONS_DISCORD_WEBHOOK_URL=/ { next }
    /^ROUGETHER_ENVIRONMENT=/ { next }
    replace_token == "true" && /^OPERATIONS_WEBEX_BOT_TOKEN=/ { next }
    replace_room == "true" && /^OPERATIONS_WEBEX_ROOM_ID=/ { next }
    { print }
  ' "$USER_RUNTIME_ENV" > "$temporary_env"

  if [ "$replace_token" = true ]; then
    printf '\nOPERATIONS_WEBEX_BOT_TOKEN=%s\n' "$(tr -d '\r\n' < "$token_file")" >> "$temporary_env"
  fi
  if [ "$replace_room" = true ]; then
    printf 'OPERATIONS_WEBEX_ROOM_ID=%s\n' "$WEBEX_ROOM_ID" >> "$temporary_env"
  fi
  printf 'ROUGETHER_ENVIRONMENT=%s\n' "$ENVIRONMENT" >> "$temporary_env"

  chmod 600 "$temporary_env"
  mv -f "$temporary_env" "$USER_RUNTIME_ENV"
  rm -f "$token_file"
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

# 주간 회고 LLM API 키를 SSM 에서 매 배포 재조회해 batch.env 에 반영한다(user-api 의 refresh_social_auth_env 와 동일 규칙).
# SSM 에 없거나 형식이 이상하면 기존 값을 유지한다 — 키가 끝내 비면 batch 는 LLM stub 으로 뜨고 회고 생성만 보류된다.
refresh_llm_env() {
  local key_file temporary_env

  if [ ! -f "$BATCH_RUNTIME_ENV" ]; then
    echo "missing batch runtime env: $BATCH_RUNTIME_ENV" >&2
    return 1
  fi

  key_file="$(mktemp "$ENV_DIR/.llm-api-key.XXXXXX")"
  if ! fetch_secret_parameter "$LLM_API_KEY_PARAMETER_NAME" "$key_file" \
      || ! single_line_secret_valid "$key_file" 512; then
    echo "LLM API key unavailable or invalid in SSM; keeping the current runtime value" >&2
    rm -f "$key_file"
    return 0
  fi

  temporary_env="$(mktemp "$ENV_DIR/.batch.env.XXXXXX")"
  awk '!/^LLM_API_KEY=/' "$BATCH_RUNTIME_ENV" > "$temporary_env"
  printf '\nLLM_API_KEY=%s\n' "$(tr -d '\r\n' < "$key_file")" >> "$temporary_env"
  chmod 600 "$temporary_env"
  mv -f "$temporary_env" "$BATCH_RUNTIME_ENV"
  rm -f "$key_file"
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

  # wall-clock 데드라인 — 횟수 기반은 실패당 최대 8초(curl 5초 + sleep 3초)씩 늘어난다.
  # t3.micro에서 user/admin JVM cold start가 10분 가까이 걸린 실측을 반영해 12분을 허용한다.
  # workflow의 SSM 감시 한도는 40분이며, 테스트에서는 env로 짧게 덮어쓴다.
  local timeout_seconds="${ROUGETHER_HEALTH_TIMEOUT_SECONDS:-720}"
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

tag_running_image_as_rollback() {
  local container_name="$1"
  local rollback_image="$2"

  if [ -z "$rollback_image" ]; then
    echo "cannot protect rollback image for $container_name: image reference is empty" >&2
    return 1
  fi

  local running_image_id
  running_image_id="$(docker inspect --format '{{.Image}}' "$container_name" 2>/dev/null || true)"
  if [ -z "$running_image_id" ]; then
    echo "cannot protect rollback image for $container_name: container is not running" >&2
    return 1
  fi

  local rollback_image_id
  rollback_image_id="$(docker image inspect --format '{{.Id}}' "$rollback_image" 2>/dev/null || true)"
  if [ -n "$rollback_image_id" ] && [ "$rollback_image_id" != "$running_image_id" ]; then
    echo "cannot protect rollback image for $container_name: state does not match the running container" >&2
    return 1
  fi

  # image prune -a can remove one of multiple tags from an image that is still in use.
  # Re-applying the deploy-state tag before and after pruning keeps rollback references valid.
  docker tag "$running_image_id" "$rollback_image"
}

protect_rollback_images() {
  local protected=true

  tag_running_image_as_rollback rougether-user-api "$rollback_user_image" || protected=false
  tag_running_image_as_rollback rougether-admin-api "$rollback_admin_image" || protected=false
  tag_running_image_as_rollback rougether-batch "$rollback_batch_image" || protected=false

  [ "$protected" = true ]
}

ensure_deploy_disk_space() {
  local minimum_free_kb="${ROUGETHER_MIN_FREE_DISK_KB:-4194304}"
  local docker_path="/var/lib/docker"
  if [ ! -d "$docker_path" ]; then
    docker_path="/"
  fi

  local available_kb
  available_kb="$(df -Pk "$docker_path" | awk 'NR == 2 {print $4}')"
  if ! [[ "$available_kb" =~ ^[0-9]+$ ]]; then
    echo "cannot determine available disk space for $docker_path" >&2
    return 1
  fi

  echo "Docker filesystem free space: $(( available_kb / 1024 )) MiB"
  if [ "$available_kb" -lt "$minimum_free_kb" ]; then
    echo "insufficient disk space before image pull: need at least $(( minimum_free_kb / 1024 )) MiB" >&2
    return 1
  fi
}

prune_unused_docker_images() {
  echo "Docker disk usage before image cleanup"
  docker system df || true

  # 현재 실행 이미지가 곧 롤백 대상이다. 셋 중 하나라도 상태가 어긋나면 삭제하지 않고
  # 여유 공간 검사만 수행해 복구 가능한 이미지를 실수로 잃지 않는다.
  if ! protect_rollback_images; then
    echo "skipping unused image cleanup because rollback images are not safely protected" >&2
    ensure_deploy_disk_space
    return
  fi

  local prune_exit_code=0
  docker image prune -a -f || prune_exit_code="$?"

  # 동일 image ID에 여러 SHA tag가 있으면 prune이 실행 중 이미지의 deploy-state tag도
  # 제거할 수 있다. 컨테이너가 보존한 image ID에 롤백 tag를 즉시 복원한다.
  protect_rollback_images || return 1

  if [ "$prune_exit_code" -ne 0 ]; then
    echo "unused Docker image cleanup failed" >&2
    return "$prune_exit_code"
  fi

  echo "Docker disk usage after image cleanup"
  docker system df || true
  ensure_deploy_disk_space
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
ExecStart=/usr/bin/docker run --rm --name rougether-admin-api --network host --env-file /etc/rougether/admin-api.env --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 ${ROUGETHER_ADMIN_API_IMAGE}
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
    # 되돌릴 이전 이미지가 있으면 그 이미지로 재기동한다. write_units 를 거치지 않는 롤백 경로
    # (user/admin 한쪽 이미지만 없어 조기 종료하는 경우)에서도 실패한 새 이미지가 아니라 이전
    # 이미지로 돌아가도록 deploy env 를 여기서 직접 이전 batch 이미지로 갱신한 뒤 재기동한다.
    # restart/health 어느 쪽이 실패해도 set -e 로 여기서 죽지 않게 감싸 원래 exit code 와 cleanup 을 보존한다.
    cat > "$BATCH_DEPLOY_ENV" <<EOF
ROUGETHER_BATCH_IMAGE=$rollback_batch_image
EOF
    chmod 600 "$BATCH_DEPLOY_ENV"

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
    # user/admin 이전 이미지가 없어도(완전 신규 박스 최초 배포) 방금 기동한 실패 batch 는
    # user/admin 가용성과 무관하게 정지해야 하므로 rollback_batch 를 먼저 부른다.
    rollback_batch
    cleanup_firebase_credentials_backup
    echo "rollback skipped: previous user-api/admin-api images are not available" >&2
    exit "$exit_code"
  fi

  write_units "$rollback_user_image" "$rollback_admin_image" "${rollback_batch_image:-$NEW_BATCH_IMAGE}"

  # batch 는 독립 유닛이라 user-api/admin-api 복구보다 먼저 처리한다 — 아래 user/admin 재기동이
  # set -e 로 실패해 스크립트가 죽더라도 batch 복구가 건너뛰어지지 않도록.
  rollback_batch

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
prune_unused_docker_images
backup_firebase_credentials
refresh_firebase_credentials
if ! ensure_user_runtime_env; then
  if ! restore_firebase_credentials; then
    echo "Firebase credential restore failed; preserving backup before exit" >&2
  fi
  cleanup_firebase_credentials_backup
  exit 1
fi
refresh_social_auth_env
refresh_webex_alert_env
refresh_admin_origin_secret_env

trap rollback ERR

aws ecr get-login-password --region "$AWS_REGION" | docker login "$REGISTRY" --username AWS --password-stdin
docker pull "$NEW_USER_IMAGE"
docker pull "$NEW_ADMIN_IMAGE"
docker pull "$NEW_BATCH_IMAGE"

write_units "$NEW_USER_IMAGE" "$NEW_ADMIN_IMAGE" "$NEW_BATCH_IMAGE"

# 병렬 기동 후 순서대로 health 확인 (rollback 경로와 동일 원칙) —
# user-api health 를 기다리는 동안 admin-api 가 함께 부팅되므로 두 번째 대기는 짧다
systemctl restart rougether-user-api rougether-admin-api
wait_health user-api http://127.0.0.1:8080/api/v1/health
wait_health admin-api http://127.0.0.1:8081/admin/health

ensure_batch_runtime_env
refresh_llm_env
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
