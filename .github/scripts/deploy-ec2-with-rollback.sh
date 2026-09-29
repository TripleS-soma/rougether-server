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
# 동거 봇(#307~#310) 활성 여부 — user-api.env 의 ROUGETHER_BOTS_ENABLED 로 내려간다(워크플로우 vars.ROUGETHER_BOTS_ENABLED, dev 기본 true)
BOTS_ENABLED="__BOTS_ENABLED__"
# 거래소 매칭 엔진(#401·#402) 활성 여부 — user-api.env 의 MARKET_ENGINE_ENABLED 로 내려간다(워크플로우 vars.MARKET_ENGINE_ENABLED, dev 기본 true).
# 모든 user-api 컨테이너가 같은 값을 받고, 리스를 가진 컨테이너 하나만 체결한다(blue/green 전환 중에도 펜싱이 이중 체결을 막음).
MARKET_ENGINE_ENABLED="__MARKET_ENGINE_ENABLED__"
DEPLOY_MODE="__DEPLOY_MODE__"

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
NGINX_CONFIG_DIR="${ROUGETHER_NGINX_CONFIG_DIR:-/etc/nginx/conf.d}"
NGINX_CONFIG_FILE="$NGINX_CONFIG_DIR/rougether.conf"
NGINX_BIN="${ROUGETHER_NGINX_BIN:-/usr/sbin/nginx}"

USER_MEMORY_LIMIT="${ROUGETHER_USER_MEMORY_LIMIT:-1280m}"
USER_MEMORY_LIMIT_KB="${ROUGETHER_USER_MEMORY_LIMIT_KB:-}"
USER_JAVA_MAX_HEAP="${ROUGETHER_USER_JAVA_MAX_HEAP:-512m}"
# 기본값은 t3.medium(3.8GB)에서 legacy 운영(세 컨테이너 동시)을 기준으로 잡는다(#418).
# 상한은 힙 + 힙 바깥(메타스페이스·JIT 코드 캐시·스레드 스택·GC 구조체 ≈ 250MB) + 여유다.
# 640m/힙 384m 에서 batch 가 JIT 컴파일 중 OOM kill(anon-rss≈649MB)된 실측을 반영해 admin/batch 를 768m 로 올린다.
ADMIN_MEMORY_LIMIT="${ROUGETHER_ADMIN_MEMORY_LIMIT:-768m}"
ADMIN_MEMORY_LIMIT_KB="${ROUGETHER_ADMIN_MEMORY_LIMIT_KB:-}"
ADMIN_JAVA_MAX_HEAP="${ROUGETHER_ADMIN_JAVA_MAX_HEAP:-384m}"
BATCH_MEMORY_LIMIT="${ROUGETHER_BATCH_MEMORY_LIMIT:-768m}"
BATCH_JAVA_MAX_HEAP="${ROUGETHER_BATCH_JAVA_MAX_HEAP:-384m}"
# *_MEMORY_LIMIT_KB 를 따로 주지 않으면 memory_limit_kb 가 상한 값(예: 1280m → 1310720)에서 계산한다.
# 힙 바깥 영역 상한과 진단 옵션 — 모든 유닛(blue/green·legacy·batch)의 JAVA_TOOL_OPTIONS 에 공통으로 붙는다.
JAVA_RESERVED_CODE_CACHE="${ROUGETHER_JAVA_RESERVED_CODE_CACHE:-128m}"
JAVA_MAX_METASPACE="${ROUGETHER_JAVA_MAX_METASPACE:-256m}"
MEMORY_RESERVE_KB="${ROUGETHER_MEMORY_RESERVE_KB:-262144}"
# 호스트 이벤트 감시(OOM kill·비정상 재시작 → 운영 Webex, #418)
WATCH_SCRIPT_PATH="${ROUGETHER_WATCH_SCRIPT_PATH:-/usr/local/libexec/rougether/container-watch.sh}"
WATCH_STATE_DIR="${ROUGETHER_WATCH_STATE_DIR:-/var/lib/rougether/watch}"
WATCH_UNIT_NAME="rougether-container-watch"
BLUE_GREEN_DRAIN_SECONDS="${ROUGETHER_DRAIN_SECONDS:-30}"

active_user_color=""
active_user_port=""
active_user_image=""
active_admin_color=""
active_admin_port=""
active_admin_image=""
active_batch_image=""
current_deployed_sha=""
current_deployment_status="ready"
current_target_sha=""
user_switched=false
admin_switched=false
batch_switched=false
legacy_from_blue_green=false
legacy_cutover_started=false
rollback_user_color=""
rollback_user_port=""
rollback_admin_color=""
rollback_admin_port=""
rollback_deployed_sha=""

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

# 동거 봇 활성 플래그를 매 배포 user-api.env 에 반영한다(멱등). 값이 true/false 가 아니면 기존 값을 유지한다 —
# 봇은 기동 시드(BotSeeder)·활동 스케줄러가 이 플래그로 켜지므로 user-api 재기동 전에 써야 한다.
refresh_bots_env() {
  local temporary_env

  if [ ! -f "$USER_RUNTIME_ENV" ]; then
    echo "missing user-api runtime env: $USER_RUNTIME_ENV" >&2
    return 1
  fi
  case "$BOTS_ENABLED" in
    true|false) ;;
    *) echo "ROUGETHER_BOTS_ENABLED value '$BOTS_ENABLED' is not true/false; keeping the current runtime value" >&2; return 0 ;;
  esac

  temporary_env="$(mktemp "$ENV_DIR/.user-api.env.XXXXXX")"
  awk '!/^ROUGETHER_BOTS_ENABLED=/' "$USER_RUNTIME_ENV" > "$temporary_env"
  printf '\nROUGETHER_BOTS_ENABLED=%s\n' "$BOTS_ENABLED" >> "$temporary_env"
  chmod 600 "$temporary_env"
  mv -f "$temporary_env" "$USER_RUNTIME_ENV"
}

# 거래소 매칭 엔진 활성 플래그를 매 배포 user-api.env 에 반영한다(멱등). 값이 true/false 가 아니면 기존 값을 유지한다 —
# 엔진 스레드·리스 heartbeat·만료 스케줄러가 기동 시 이 플래그로 켜지므로 user-api 재기동 전에 써야 한다.
refresh_market_engine_env() {
  local temporary_env

  if [ ! -f "$USER_RUNTIME_ENV" ]; then
    echo "missing user-api runtime env: $USER_RUNTIME_ENV" >&2
    return 1
  fi
  case "$MARKET_ENGINE_ENABLED" in
    true|false) ;;
    *) echo "MARKET_ENGINE_ENABLED value '$MARKET_ENGINE_ENABLED' is not true/false; keeping the current runtime value" >&2; return 0 ;;
  esac

  temporary_env="$(mktemp "$ENV_DIR/.user-api.env.XXXXXX")"
  awk '!/^MARKET_ENGINE_ENABLED=/' "$USER_RUNTIME_ENV" > "$temporary_env"
  printf '\nMARKET_ENGINE_ENABLED=%s\n' "$MARKET_ENGINE_ENABLED" >> "$temporary_env"
  chmod 600 "$temporary_env"
  mv -f "$temporary_env" "$USER_RUNTIME_ENV"
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

# LLM API 키를 SSM 에서 매 배포 재조회해 지정한 runtime env 파일에 반영한다(refresh_social_auth_env 와 동일 규칙).
# batch(주간 회고)와 user-api(유사 루틴 비교 임베딩, #303)가 같은 키를 쓴다. SSM 에 없거나 형식이 이상하면 기존 값을
# 유지한다 — 키가 끝내 비면 해당 서비스는 LLM stub 으로 뜨고 회고 생성 보류·유사도는 정규화 일치만으로 동작한다.
refresh_llm_env() {
  local target_env="$1"
  local key_file temporary_env

  if [ ! -f "$target_env" ]; then
    echo "missing runtime env: $target_env" >&2
    return 1
  fi

  key_file="$(mktemp "$ENV_DIR/.llm-api-key.XXXXXX")"
  if ! fetch_secret_parameter "$LLM_API_KEY_PARAMETER_NAME" "$key_file" \
      || ! single_line_secret_valid "$key_file" 512; then
    echo "LLM API key unavailable or invalid in SSM; keeping the current runtime value" >&2
    rm -f "$key_file"
    return 0
  fi

  temporary_env="$(mktemp "$ENV_DIR/.llm-env.XXXXXX")"
  awk '!/^LLM_API_KEY=/' "$target_env" > "$temporary_env"
  printf '\nLLM_API_KEY=%s\n' "$(tr -d '\r\n' < "$key_file")" >> "$temporary_env"
  chmod 600 "$temporary_env"
  mv -f "$temporary_env" "$target_env"
  rm -f "$key_file"
}

runtime_env_value() {
  local env_file="$1"
  local key="$2"
  awk -v key="$key" 'index($0, key "=") == 1 {value = substr($0, length(key) + 2)} END {printf "%s", value}' "$env_file"
}

# batch 운영 알림(#417)이 user-api 와 같은 Webex 봇·room 을 쓰도록 user-api.env 의 값을 batch.env 로 복사한다(멱등).
# refresh_webex_alert_env 가 먼저 user-api.env 를 갱신한 뒤 호출한다. 값이 비었거나 형식이 이상하면 기존 batch 값을 유지한다.
refresh_batch_webex_alert_env() {
  local token_file room_id environment temporary_env replace_token=false replace_room=false replace_environment=false

  if [ ! -f "$BATCH_RUNTIME_ENV" ]; then
    echo "missing batch runtime env: $BATCH_RUNTIME_ENV" >&2
    return 1
  fi
  if [ ! -f "$USER_RUNTIME_ENV" ]; then
    echo "missing user-api runtime env: $USER_RUNTIME_ENV" >&2
    return 1
  fi

  token_file="$(mktemp "$ENV_DIR/.webex-bot-token.XXXXXX")"
  runtime_env_value "$USER_RUNTIME_ENV" OPERATIONS_WEBEX_BOT_TOKEN > "$token_file"
  if webex_bot_token_valid "$token_file"; then
    replace_token=true
  else
    echo "user-api Webex bot token is missing or invalid; keeping the current batch token" >&2
  fi

  room_id="$(runtime_env_value "$USER_RUNTIME_ENV" OPERATIONS_WEBEX_ROOM_ID)"
  if [ -n "$room_id" ] && [ "${#room_id}" -le 1024 ] && [[ "$room_id" != *[[:space:]]* ]]; then
    replace_room=true
  else
    echo "user-api Webex room ID is missing or invalid; keeping the current batch room ID" >&2
  fi

  environment="$(runtime_env_value "$USER_RUNTIME_ENV" ROUGETHER_ENVIRONMENT)"
  if [[ "$environment" =~ ^[A-Za-z0-9._-]{1,64}$ ]]; then
    replace_environment=true
  fi

  temporary_env="$(mktemp "$ENV_DIR/.batch.env.XXXXXX")"
  awk -v replace_token="$replace_token" -v replace_room="$replace_room" -v replace_environment="$replace_environment" '
    replace_token == "true" && /^OPERATIONS_WEBEX_BOT_TOKEN=/ { next }
    replace_room == "true" && /^OPERATIONS_WEBEX_ROOM_ID=/ { next }
    replace_environment == "true" && /^ROUGETHER_ENVIRONMENT=/ { next }
    { print }
  ' "$BATCH_RUNTIME_ENV" > "$temporary_env"

  # 매 배포 빈 줄이 쌓이지 않도록 마지막 줄바꿈만 보장한다.
  if [ -s "$temporary_env" ] && [ -n "$(tail -c 1 "$temporary_env")" ]; then
    printf '\n' >> "$temporary_env"
  fi
  if [ "$replace_token" = true ]; then
    printf 'OPERATIONS_WEBEX_BOT_TOKEN=%s\n' "$(tr -d '\r\n' < "$token_file")" >> "$temporary_env"
  fi
  if [ "$replace_room" = true ]; then
    printf 'OPERATIONS_WEBEX_ROOM_ID=%s\n' "$room_id" >> "$temporary_env"
  fi
  if [ "$replace_environment" = true ]; then
    printf 'ROUGETHER_ENVIRONMENT=%s\n' "$environment" >> "$temporary_env"
  fi

  chmod 600 "$temporary_env"
  mv -f "$temporary_env" "$BATCH_RUNTIME_ENV"
  rm -f "$token_file"
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
  local user_container="rougether-user-api"
  local admin_container="rougether-admin-api"

  if [ -n "$active_user_color" ]; then
    user_container="$(slot_container user-api "$active_user_color")"
  fi
  if [ -n "$active_admin_color" ]; then
    admin_container="$(slot_container admin-api "$active_admin_color")"
  fi

  tag_running_image_as_rollback "$user_container" "$rollback_user_image" || protected=false
  tag_running_image_as_rollback "$admin_container" "$rollback_admin_image" || protected=false
  tag_running_image_as_rollback rougether-batch "$rollback_batch_image" || protected=false

  # A previous interrupted deployment can leave an inactive candidate behind. Its image
  # reference is kept in the slot env file; protect that tag before and after prune too.
  local service color candidate_container candidate_env candidate_image
  for service in user-api admin-api; do
    for color in blue green; do
      candidate_container="$(slot_container "$service" "$color")"
      candidate_env="$(slot_env_file "$service" "$color")"
      if docker inspect --format '{{.Image}}' "$candidate_container" >/dev/null 2>&1 \
          && [ -f "$candidate_env" ]; then
        candidate_image="$(awk -F= '$1 == "ROUGETHER_IMAGE" {print substr($0, index($0, "=") + 1); exit}' "$candidate_env")"
        tag_running_image_as_rollback "$candidate_container" "$candidate_image" || protected=false
      fi
    done
  done

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

slot_port() {
  local service="$1"
  local color="$2"

  case "$service:$color" in
    user-api:blue) echo 18080 ;;
    user-api:green) echo 28080 ;;
    admin-api:blue) echo 18081 ;;
    admin-api:green) echo 28081 ;;
    *) echo "invalid blue/green slot: $service/$color" >&2; return 1 ;;
  esac
}

inactive_color() {
  case "$1" in
    blue) echo green ;;
    green) echo blue ;;
    "") echo blue ;;
    *) echo "invalid active color: $1" >&2; return 1 ;;
  esac
}

slot_unit() {
  echo "rougether-$1@$2.service"
}

slot_container() {
  echo "rougether-$1-$2"
}

slot_env_file() {
  echo "$ENV_DIR/$1-$2.deploy.env"
}

service_health_url() {
  local service="$1"
  local port="$2"

  case "$service" in
    user-api) echo "http://127.0.0.1:$port/api/v1/health" ;;
    admin-api) echo "http://127.0.0.1:$port/admin/health" ;;
    *) echo "invalid API service: $service" >&2; return 1 ;;
  esac
}

stable_health_url() {
  case "$1" in
    user-api) echo "http://127.0.0.1:8080/api/v1/health" ;;
    admin-api) echo "http://127.0.0.1:8081/admin/health" ;;
    *) echo "invalid API service: $1" >&2; return 1 ;;
  esac
}

load_blue_green_state() {
  active_user_color=""
  active_user_port=""
  active_user_image=""
  active_admin_color=""
  active_admin_port=""
  active_admin_image=""
  active_batch_image=""
  current_deployed_sha=""
  current_deployment_status="ready"
  current_target_sha=""
  rollback_user_image=""
  rollback_user_color=""
  rollback_user_port=""
  rollback_admin_image=""
  rollback_admin_color=""
  rollback_admin_port=""
  rollback_batch_image=""
  rollback_deployed_sha=""

  if [ ! -f "$STATE_FILE" ]; then
    local service color
    for service in user-api admin-api; do
      for color in blue green; do
        if docker inspect --format '{{.Image}}' "$(slot_container "$service" "$color")" >/dev/null 2>&1; then
          echo "deploy state is missing while blue/green containers exist" >&2
          return 1
        fi
      done
    done
    return 0
  fi

  while IFS='=' read -r key value; do
    case "$key" in
      USER_API_IMAGE) active_user_image="$value" ;;
      USER_API_ACTIVE_COLOR) active_user_color="$value" ;;
      USER_API_ACTIVE_PORT) active_user_port="$value" ;;
      ADMIN_API_IMAGE) active_admin_image="$value" ;;
      ADMIN_API_ACTIVE_COLOR) active_admin_color="$value" ;;
      ADMIN_API_ACTIVE_PORT) active_admin_port="$value" ;;
      BATCH_API_IMAGE) active_batch_image="$value" ;;
      DEPLOYMENT_STATUS) current_deployment_status="$value" ;;
      DEPLOYED_SHA) current_deployed_sha="$value" ;;
      TARGET_SHA) current_target_sha="$value" ;;
      ROLLBACK_USER_API_IMAGE) rollback_user_image="$value" ;;
      ROLLBACK_USER_API_ACTIVE_COLOR) rollback_user_color="$value" ;;
      ROLLBACK_USER_API_ACTIVE_PORT) rollback_user_port="$value" ;;
      ROLLBACK_ADMIN_API_IMAGE) rollback_admin_image="$value" ;;
      ROLLBACK_ADMIN_API_ACTIVE_COLOR) rollback_admin_color="$value" ;;
      ROLLBACK_ADMIN_API_ACTIVE_PORT) rollback_admin_port="$value" ;;
      ROLLBACK_BATCH_API_IMAGE) rollback_batch_image="$value" ;;
      ROLLBACK_DEPLOYED_SHA) rollback_deployed_sha="$value" ;;
    esac
  done < "$STATE_FILE"

  if [ -n "$active_user_color" ] || [ -n "$active_user_port" ]; then
    [ -n "$active_user_color" ] \
      && [ -n "$active_user_port" ] \
      && [ "$(slot_port user-api "$active_user_color")" = "$active_user_port" ] || {
      echo "user-api deploy state has an invalid color/port pair" >&2
      return 1
    }
  fi
  if [ -n "$active_admin_color" ] || [ -n "$active_admin_port" ]; then
    [ -n "$active_admin_color" ] \
      && [ -n "$active_admin_port" ] \
      && [ "$(slot_port admin-api "$active_admin_color")" = "$active_admin_port" ] || {
      echo "admin-api deploy state has an invalid color/port pair" >&2
      return 1
    }
  fi

  case "$current_deployment_status" in
    ready) ;;
    deploying)
      [ -n "$current_target_sha" ] \
        && [ -n "$rollback_user_image" ] \
        && [ -n "$rollback_admin_image" ] \
        && [ -n "$rollback_batch_image" ] \
        && [ -n "$rollback_deployed_sha" ] || {
          echo "deploying state is missing its stable rollback snapshot" >&2
          return 1
        }
      if [ -n "$rollback_user_color" ] || [ -n "$rollback_user_port" ]; then
        [ -n "$rollback_user_color" ] \
          && [ -n "$rollback_user_port" ] \
          && [ "$(slot_port user-api "$rollback_user_color")" = "$rollback_user_port" ] || {
          echo "user-api rollback state has an invalid color/port pair" >&2
          return 1
        }
      fi
      if [ -n "$rollback_admin_color" ] || [ -n "$rollback_admin_port" ]; then
        [ -n "$rollback_admin_color" ] \
          && [ -n "$rollback_admin_port" ] \
          && [ "$(slot_port admin-api "$rollback_admin_color")" = "$rollback_admin_port" ] || {
          echo "admin-api rollback state has an invalid color/port pair" >&2
          return 1
        }
      fi
      ;;
    *)
      echo "invalid deployment status: $current_deployment_status" >&2
      return 1
      ;;
  esac

  if { [ -n "$active_user_color" ] || [ -n "$active_admin_color" ]; } \
      && { [ -z "$active_user_image" ] || [ -z "$active_admin_image" ] || [ -z "$active_batch_image" ]; }; then
    echo "blue/green deploy state is incomplete" >&2
    return 1
  fi

  if [ -n "$active_user_color" ] || [ -n "$active_admin_color" ]; then
    [ -f "$NGINX_CONFIG_FILE" ] || {
      echo "blue/green state exists but Nginx routing config is missing" >&2
      return 1
    }
    if [ -n "$active_user_color" ]; then
      grep -q "server 127.0.0.1:$active_user_port;" "$NGINX_CONFIG_FILE" || {
        echo "Nginx user upstream does not match deploy state" >&2
        return 1
      }
    elif grep -Eq '^[[:space:]]*listen[[:space:]]+8080' "$NGINX_CONFIG_FILE"; then
      echo "Nginx owns user port 8080 without an active user slot" >&2
      return 1
    fi
    if [ -n "$active_admin_color" ]; then
      grep -Eq "server [^;]+:$active_admin_port;" "$NGINX_CONFIG_FILE" || {
        echo "Nginx admin upstream does not match deploy state" >&2
        return 1
      }
    elif grep -Eq '^[[:space:]]*listen[[:space:]]+8081' "$NGINX_CONFIG_FILE"; then
      echo "Nginx owns admin port 8081 without an active admin slot" >&2
      return 1
    fi
  elif [ -f "$NGINX_CONFIG_FILE" ] \
      && grep -Eq '^[[:space:]]*listen[[:space:]]+8080|^[[:space:]]*listen[[:space:]]+8081' "$NGINX_CONFIG_FILE"; then
    echo "Nginx owns a fixed API port but deploy state has no active slot" >&2
    return 1
  fi
}

write_blue_green_state() {
  local status="$1"
  local deployed_sha="$2"
  local target_sha="$DEPLOYED_SHA"
  local temporary_state
  local state_rollback_user_image=""
  local state_rollback_user_color=""
  local state_rollback_user_port=""
  local state_rollback_admin_image=""
  local state_rollback_admin_color=""
  local state_rollback_admin_port=""
  local state_rollback_batch_image=""
  local state_rollback_deployed_sha=""

  case "$status" in
    ready)
      target_sha="$deployed_sha"
      ;;
    deploying)
      [ -n "$rollback_user_image" ] \
        && [ -n "$rollback_admin_image" ] \
        && [ -n "$rollback_batch_image" ] \
        && [ -n "$rollback_deployed_sha" ] || {
          echo "cannot checkpoint a deployment without a complete rollback snapshot" >&2
          return 1
        }
      state_rollback_user_image="$rollback_user_image"
      state_rollback_user_color="$rollback_user_color"
      state_rollback_user_port="$rollback_user_port"
      state_rollback_admin_image="$rollback_admin_image"
      state_rollback_admin_color="$rollback_admin_color"
      state_rollback_admin_port="$rollback_admin_port"
      state_rollback_batch_image="$rollback_batch_image"
      state_rollback_deployed_sha="$rollback_deployed_sha"
      ;;
    *)
      echo "invalid deployment status: $status" >&2
      return 1
      ;;
  esac

  temporary_state="$(mktemp "$ENV_DIR/.deploy-state.env.XXXXXX")"
  cat > "$temporary_state" <<EOF
USER_API_IMAGE=$active_user_image
USER_API_ACTIVE_COLOR=$active_user_color
USER_API_ACTIVE_PORT=$active_user_port
ADMIN_API_IMAGE=$active_admin_image
ADMIN_API_ACTIVE_COLOR=$active_admin_color
ADMIN_API_ACTIVE_PORT=$active_admin_port
BATCH_API_IMAGE=$active_batch_image
DEPLOYMENT_STATUS=$status
DEPLOYED_SHA=$deployed_sha
TARGET_SHA=$target_sha
ROLLBACK_USER_API_IMAGE=$state_rollback_user_image
ROLLBACK_USER_API_ACTIVE_COLOR=$state_rollback_user_color
ROLLBACK_USER_API_ACTIVE_PORT=$state_rollback_user_port
ROLLBACK_ADMIN_API_IMAGE=$state_rollback_admin_image
ROLLBACK_ADMIN_API_ACTIVE_COLOR=$state_rollback_admin_color
ROLLBACK_ADMIN_API_ACTIVE_PORT=$state_rollback_admin_port
ROLLBACK_BATCH_API_IMAGE=$state_rollback_batch_image
ROLLBACK_DEPLOYED_SHA=$state_rollback_deployed_sha
DEPLOYED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
  chmod 600 "$temporary_state"
  mv -f "$temporary_state" "$STATE_FILE"
}

memory_size_to_kb() {
  local size="$1"
  local number="${size%[kmg]}"
  [[ "$size" =~ ^[1-9][0-9]*[kmg]$ ]] || { echo "invalid memory size: $size" >&2; return 1; }
  case "$size" in
    *k) echo "$number" ;;
    *m) echo $(( number * 1024 )) ;;
    *g) echo $(( number * 1024 * 1024 )) ;;
  esac
}

memory_limit_kb() {
  case "$1" in
    user-api)
      if [ -n "$USER_MEMORY_LIMIT_KB" ]; then echo "$USER_MEMORY_LIMIT_KB"; else memory_size_to_kb "$USER_MEMORY_LIMIT"; fi
      ;;
    admin-api)
      if [ -n "$ADMIN_MEMORY_LIMIT_KB" ]; then echo "$ADMIN_MEMORY_LIMIT_KB"; else memory_size_to_kb "$ADMIN_MEMORY_LIMIT"; fi
      ;;
    *) echo "invalid API service: $1" >&2; return 1 ;;
  esac
}

check_memory_budget() {
  local service="$1"
  local meminfo_path="${ROUGETHER_MEMINFO_PATH:-/proc/meminfo}"
  local available_kb required_kb limit_kb

  available_kb="$(awk '/^MemAvailable:/ {print $2; exit}' "$meminfo_path")"
  if ! [[ "$available_kb" =~ ^[0-9]+$ ]]; then
    echo "cannot determine MemAvailable from $meminfo_path" >&2
    return 1
  fi

  limit_kb="$(memory_limit_kb "$service")" || return 1
  required_kb=$(( limit_kb + MEMORY_RESERVE_KB ))
  echo "$service memory preflight: available=${available_kb}KiB required=${required_kb}KiB"
  if [ "$available_kb" -lt "$required_kb" ]; then
    echo "insufficient memory to start $service candidate" >&2
    return 1
  fi
}

log_memory_snapshot() {
  local meminfo_path="${ROUGETHER_MEMINFO_PATH:-/proc/meminfo}"
  local available_kb swap_total_kb swap_free_kb

  available_kb="$(awk '/^MemAvailable:/ {print $2; exit}' "$meminfo_path")"
  swap_total_kb="$(awk '/^SwapTotal:/ {print $2; exit}' "$meminfo_path")"
  swap_free_kb="$(awk '/^SwapFree:/ {print $2; exit}' "$meminfo_path")"
  if [[ "$available_kb" =~ ^[0-9]+$ ]] \
      && [[ "$swap_total_kb" =~ ^[0-9]+$ ]] \
      && [[ "$swap_free_kb" =~ ^[0-9]+$ ]]; then
    echo "memory snapshot: MemAvailable=${available_kb}KiB SwapUsed=$(( swap_total_kb - swap_free_kb ))KiB"
  fi
}

wait_health_stable() {
  local name="$1"
  local url="$2"
  local timeout_seconds="${ROUGETHER_HEALTH_TIMEOUT_SECONDS:-180}"
  local required_successes="${ROUGETHER_HEALTH_REQUIRED_SUCCESSES:-3}"
  local deadline=$(( SECONDS + timeout_seconds ))
  local consecutive=0

  while [ "$SECONDS" -lt "$deadline" ]; do
    if curl -fsS --connect-timeout 2 --max-time 5 "$url" >/dev/null; then
      consecutive=$(( consecutive + 1 ))
      if [ "$consecutive" -ge "$required_successes" ]; then
        echo "$name health check passed $required_successes consecutive times"
        return 0
      fi
    else
      consecutive=0
    fi
    sleep 2
  done

  echo "$name health check failed to become stable" >&2
  return 1
}

memory_size_valid() {
  [[ "$1" =~ ^[1-9][0-9]*[kmg]$ ]]
}

# 유닛 파일에 그대로 구워지는 값이라 ROUGETHER_* 로 덮을 때 형식을 강제한다(공백·따옴표·% 가 섞이면 ExecStart 가 깨진다).
validate_memory_settings() {
  local value
  for value in "$USER_MEMORY_LIMIT" "$USER_JAVA_MAX_HEAP" \
      "$ADMIN_MEMORY_LIMIT" "$ADMIN_JAVA_MAX_HEAP" \
      "$BATCH_MEMORY_LIMIT" "$BATCH_JAVA_MAX_HEAP" \
      "$JAVA_RESERVED_CODE_CACHE" "$JAVA_MAX_METASPACE"; do
    if ! memory_size_valid "$value"; then
      echo "invalid memory setting '$value' (expected <number>[k|m|g])" >&2
      return 1
    fi
  done
}

java_tool_options() {
  local max_heap="$1"
  printf -- '-Xmx%s -XX:ReservedCodeCacheSize=%s -XX:MaxMetaspaceSize=%s -XX:+ExitOnOutOfMemoryError -XX:NativeMemoryTracking=summary' \
    "$max_heap" "$JAVA_RESERVED_CODE_CACHE" "$JAVA_MAX_METASPACE"
}

# docker run 의 메모리 상한과 JVM 옵션 인자. JAVA_TOOL_OPTIONS 값에 공백이 있으므로 systemd ExecStart 의
# 큰따옴표 인용으로 한 인자(--env "JAVA_TOOL_OPTIONS=...")로 넘긴다. 값은 validate_memory_settings 로
# [0-9kmg] 만 허용하므로 따옴표·$·% 가 섞일 수 없다. 비밀값이 든 runtime env 파일은 건드리지 않는다.
container_memory_args() {
  local memory_limit="$1"
  local max_heap="$2"
  printf -- '--memory %s --memory-swap %s --env "JAVA_TOOL_OPTIONS=%s"' \
    "$memory_limit" "$memory_limit" "$(java_tool_options "$max_heap")"
}

write_slot_env() {
  local service="$1"
  local color="$2"
  local image="$3"
  local port
  port="$(slot_port "$service" "$color")"

  cat > "$(slot_env_file "$service" "$color")" <<EOF
ROUGETHER_IMAGE=$image
ROUGETHER_HOST_PORT=$port
EOF
  chmod 600 "$(slot_env_file "$service" "$color")"
}

write_blue_green_units() {
  local firebase_mount_option=""
  local user_memory_args admin_memory_args batch_memory_args

  validate_memory_settings || return 1
  user_memory_args="$(container_memory_args "$USER_MEMORY_LIMIT" "$USER_JAVA_MAX_HEAP")"
  admin_memory_args="$(container_memory_args "$ADMIN_MEMORY_LIMIT" "$ADMIN_JAVA_MAX_HEAP")"
  batch_memory_args="$(container_memory_args "$BATCH_MEMORY_LIMIT" "$BATCH_JAVA_MAX_HEAP")"

  if firebase_credentials_valid "$FIREBASE_CREDENTIALS_FILE"; then
    firebase_mount_option="-v /etc/rougether/firebase-adminsdk.json:/etc/rougether/firebase-adminsdk.json:ro"
  fi

  mkdir -p "$ENV_DIR" "$SYSTEMD_DIR"
  chmod 700 "$ENV_DIR"

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
ExecStart=/usr/bin/docker run --rm --name rougether-user-api-%i $user_memory_args --env-file /etc/rougether/user-api.env $firebase_mount_option -p 127.0.0.1:\${ROUGETHER_HOST_PORT}:8080 --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 \${ROUGETHER_IMAGE}
ExecStop=/usr/bin/docker stop --time 30 rougether-user-api-%i
TimeoutStopSec=45

[Install]
WantedBy=multi-user.target
EOF

  cat > "$SYSTEMD_DIR/rougether-admin-api@.service" <<EOF
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
ExecStart=/usr/bin/docker run --rm --name rougether-admin-api-%i --network host $admin_memory_args --env-file /etc/rougether/admin-api.env --env SERVER_PORT=\${ROUGETHER_HOST_PORT} --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 \${ROUGETHER_IMAGE}
ExecStop=/usr/bin/docker stop --time 30 rougether-admin-api-%i
TimeoutStopSec=45

[Install]
WantedBy=multi-user.target
EOF

  cat > "$SYSTEMD_DIR/rougether-batch.service" <<EOF
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
ExecStart=/usr/bin/docker run --rm --name rougether-batch $batch_memory_args --env-file /etc/rougether/batch.env $firebase_mount_option -p 127.0.0.1:8082:8082 --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 \${ROUGETHER_BATCH_IMAGE}
ExecStop=/usr/bin/docker stop --time 30 rougether-batch
TimeoutStopSec=45

[Install]
WantedBy=multi-user.target
EOF

  chmod 644 "$SYSTEMD_DIR/rougether-user-api@.service" \
    "$SYSTEMD_DIR/rougether-admin-api@.service" \
    "$SYSTEMD_DIR/rougether-batch.service"
  systemctl daemon-reload
  systemctl enable rougether-batch
}

private_upstream_ip() {
  if [ -n "${ROUGETHER_PRIVATE_IP:-}" ]; then
    echo "$ROUGETHER_PRIVATE_IP"
    return 0
  fi

  ip -4 route get 1.1.1.1 | awk '{for (i = 1; i <= NF; i++) if ($i == "src") {print $(i + 1); exit}}'
}

render_nginx_config() {
  local destination="$1"
  local user_port="$2"
  local admin_port="$3"
  local admin_upstream_ip
  admin_upstream_ip="$(private_upstream_ip)"

  if [ -n "$admin_port" ] && [ -z "$admin_upstream_ip" ]; then
    echo "cannot determine the EC2 private IP for admin upstream" >&2
    return 1
  fi

  {
    echo "# Generated by deploy-ec2-with-rollback.sh; do not edit manually."
    if [ -n "$user_port" ]; then
      cat <<EOF
upstream rougether_user_api {
    server 127.0.0.1:$user_port;
    keepalive 16;
}

server {
    listen 8080;
    client_max_body_size 40m;

    location = /api/v1/chat/ws {
        proxy_pass http://rougether_user_api;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$http_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$http_x_forwarded_proto;
        proxy_read_timeout 60s;
    }

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
upstream rougether_admin_api {
    server $admin_upstream_ip:$admin_port;
    keepalive 8;
}

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

ensure_nginx_installed() {
  if [ ! -x "$NGINX_BIN" ]; then
    dnf install -y nginx
  fi
  mkdir -p "$NGINX_CONFIG_DIR"
}

apply_proxy_ports() {
  local user_port="$1"
  local admin_port="$2"
  local temporary_config backup_config=""

  ensure_nginx_installed || return 1
  temporary_config="$(mktemp "$NGINX_CONFIG_DIR/.rougether.conf.XXXXXX")" || return 1
  if ! render_nginx_config "$temporary_config" "$user_port" "$admin_port"; then
    rm -f "$temporary_config"
    return 1
  fi

  if [ -f "$NGINX_CONFIG_FILE" ]; then
    backup_config="$(mktemp "$NGINX_CONFIG_DIR/.rougether.rollback.XXXXXX")"
    cp -p "$NGINX_CONFIG_FILE" "$backup_config"
  fi

  chmod 644 "$temporary_config"
  mv -f "$temporary_config" "$NGINX_CONFIG_FILE"

  if ! "$NGINX_BIN" -t; then
    if [ -n "$backup_config" ]; then
      mv -f "$backup_config" "$NGINX_CONFIG_FILE"
    else
      rm -f "$NGINX_CONFIG_FILE"
    fi
    echo "nginx configuration validation failed; previous upstream remains active" >&2
    return 1
  fi

  if systemctl is-active --quiet nginx; then
    if ! systemctl reload nginx; then
      if [ -n "$backup_config" ]; then
        mv -f "$backup_config" "$NGINX_CONFIG_FILE"
        "$NGINX_BIN" -t && systemctl reload nginx || true
      else
        rm -f "$NGINX_CONFIG_FILE"
      fi
      echo "nginx reload failed; restored previous upstream" >&2
      return 1
    fi
  else
    if ! systemctl enable --now nginx; then
      if [ -n "$backup_config" ]; then
        mv -f "$backup_config" "$NGINX_CONFIG_FILE"
      else
        rm -f "$NGINX_CONFIG_FILE"
      fi
      echo "nginx start failed" >&2
      return 1
    fi
  fi

  [ -z "$backup_config" ] || rm -f "$backup_config"
}

start_candidate() {
  local service="$1"
  local color="$2"
  local image="$3"
  local port unit

  log_memory_snapshot
  check_memory_budget "$service" || return 1
  port="$(slot_port "$service" "$color")" || return 1
  unit="$(slot_unit "$service" "$color")"
  write_slot_env "$service" "$color" "$image" || return 1

  systemctl stop "$unit" >/dev/null 2>&1 || true
  docker rm -f "$(slot_container "$service" "$color")" >/dev/null 2>&1 || true
  systemctl start "$unit" || return 1
  wait_health_stable "$service-$color" "$(service_health_url "$service" "$port")" || return 1
}

stop_slot() {
  local service="$1"
  local color="$2"
  [ -n "$color" ] || return 0

  systemctl disable "$(slot_unit "$service" "$color")" >/dev/null 2>&1 || true
  systemctl stop "$(slot_unit "$service" "$color")" >/dev/null 2>&1 || true
  docker rm -f "$(slot_container "$service" "$color")" >/dev/null 2>&1 || true
}

deploy_api_blue_green() {
  local service="$1"
  local new_image="$2"
  local previous_color candidate_color candidate_port
  local target_user_port="$active_user_port"
  local target_admin_port="$active_admin_port"
  local initial_cutover=false

  case "$service" in
    user-api)
      previous_color="$active_user_color"
      ;;
    admin-api)
      previous_color="$active_admin_color"
      ;;
    *) echo "invalid API service: $service" >&2; return 1 ;;
  esac

  candidate_color="$(inactive_color "$previous_color")"
  candidate_port="$(slot_port "$service" "$candidate_color")"
  start_candidate "$service" "$candidate_color" "$new_image" || return 1

  if [ -z "$previous_color" ]; then
    initial_cutover=true
    if ! systemctl stop "rougether-$service"; then
      stop_slot "$service" "$candidate_color"
      return 1
    fi
  fi

  case "$service" in
    user-api) target_user_port="$candidate_port" ;;
    admin-api) target_admin_port="$candidate_port" ;;
  esac

  if ! apply_proxy_ports "$target_user_port" "$target_admin_port"; then
    if [ "$initial_cutover" = true ]; then
      systemctl start "rougether-$service" || true
    fi
    stop_slot "$service" "$candidate_color"
    return 1
  fi

  case "$service" in
    user-api)
      active_user_color="$candidate_color"
      active_user_port="$candidate_port"
      active_user_image="$new_image"
      user_switched=true
      ;;
    admin-api)
      active_admin_color="$candidate_color"
      active_admin_port="$candidate_port"
      active_admin_image="$new_image"
      admin_switched=true
      ;;
  esac

  systemctl enable "$(slot_unit "$service" "$candidate_color")" || return 1
  write_blue_green_state deploying "${current_deployed_sha:-bootstrap}" || return 1
  wait_health_stable "$service-stable" "$(stable_health_url "$service")" || return 1

  if [ "$initial_cutover" = true ]; then
    systemctl disable "rougether-$service" >/dev/null 2>&1 || true
  else
    sleep "$BLUE_GREEN_DRAIN_SECONDS"
    stop_slot "$service" "$previous_color"
  fi
}

rollback_api_blue_green() {
  local service="$1"
  local previous_image="$2"
  local previous_color="$3"
  local previous_port="$4"
  local failed_color target_user_port="$active_user_port" target_admin_port="$active_admin_port"

  case "$service" in
    user-api) failed_color="$active_user_color" ;;
    admin-api) failed_color="$active_admin_color" ;;
    *) echo "invalid API service: $service" >&2; return 1 ;;
  esac

  echo "$service rollback: failed_color=${failed_color:-legacy} previous_color=${previous_color:-legacy}"
  if [ -n "$previous_color" ]; then
    start_candidate "$service" "$previous_color" "$previous_image" || return 1
    case "$service" in
      user-api) target_user_port="$previous_port" ;;
      admin-api) target_admin_port="$previous_port" ;;
    esac
    apply_proxy_ports "$target_user_port" "$target_admin_port" || return 1
  else
    # On the first migration, release the fixed port from Nginx before bringing
    # the legacy unit back because it owns 8080/8081 directly.
    case "$service" in
      user-api) target_user_port="" ;;
      admin-api) target_admin_port="" ;;
    esac
    apply_proxy_ports "$target_user_port" "$target_admin_port" || return 1
    systemctl enable "rougether-$service" || return 1
    systemctl start "rougether-$service" || return 1
  fi

  case "$service" in
    user-api)
      active_user_color="$previous_color"
      active_user_port="$previous_port"
      active_user_image="$previous_image"
      ;;
    admin-api)
      active_admin_color="$previous_color"
      active_admin_port="$previous_port"
      active_admin_image="$previous_image"
      ;;
  esac

  wait_health_stable "$service-rollback" "$(stable_health_url "$service")" || return 1
  if [ -n "$failed_color" ] && [ "$failed_color" != "$previous_color" ]; then
    stop_slot "$service" "$failed_color"
  fi
}

blue_green_release_is_active() {
  [ "$current_deployment_status" = ready ] \
    && [ "$current_deployed_sha" = "$DEPLOYED_SHA" ] \
    && [ "$active_user_image" = "$NEW_USER_IMAGE" ] \
    && [ "$active_admin_image" = "$NEW_ADMIN_IMAGE" ] \
    && [ "$active_batch_image" = "$NEW_BATCH_IMAGE" ] \
    && [ -n "$active_user_color" ] \
    && [ -n "$active_admin_color" ]
}

write_units() {
  local user_image="$1"
  local admin_image="$2"
  local batch_image="$3"
  local firebase_mount_option=""
  local user_service_file="$SYSTEMD_DIR/rougether-user-api.service"
  local admin_service_file="$SYSTEMD_DIR/rougether-admin-api.service"
  local batch_service_file="$SYSTEMD_DIR/rougether-batch.service"
  local user_memory_args admin_memory_args batch_memory_args

  # legacy 유닛도 blue/green 과 같은 상한·JVM 옵션을 쓴다 — 없으면 세 컨테이너가 상한 없이 호스트 메모리를 나눠 쓴다(#418).
  validate_memory_settings || return 1
  user_memory_args="$(container_memory_args "$USER_MEMORY_LIMIT" "$USER_JAVA_MAX_HEAP")"
  admin_memory_args="$(container_memory_args "$ADMIN_MEMORY_LIMIT" "$ADMIN_JAVA_MAX_HEAP")"
  batch_memory_args="$(container_memory_args "$BATCH_MEMORY_LIMIT" "$BATCH_JAVA_MAX_HEAP")"

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
ExecStart=/usr/bin/docker run --rm --name rougether-user-api $user_memory_args --env-file /etc/rougether/user-api.env $firebase_mount_option -p 8080:8080 --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 \${ROUGETHER_USER_API_IMAGE}
ExecStop=/usr/bin/docker stop rougether-user-api

[Install]
WantedBy=multi-user.target
EOF

  cat > "$admin_service_file" <<EOF
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
ExecStart=/usr/bin/docker run --rm --name rougether-admin-api --network host $admin_memory_args --env-file /etc/rougether/admin-api.env --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 \${ROUGETHER_ADMIN_API_IMAGE}
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
ExecStart=/usr/bin/docker run --rm --name rougether-batch $batch_memory_args --env-file /etc/rougether/batch.env $firebase_mount_option -p 127.0.0.1:8082:8082 --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 \${ROUGETHER_BATCH_IMAGE}
ExecStop=/usr/bin/docker stop rougether-batch

[Install]
WantedBy=multi-user.target
EOF

  systemctl daemon-reload
  systemctl enable rougether-user-api rougether-admin-api rougether-batch
}

capture_rollback_images() {
  # A checkpoint with status=deploying contains both the currently routed slots and
  # the last complete release. Keep the explicit rollback snapshot on retries instead
  # of mistaking a partially switched candidate for the stable release.
  if [ "$current_deployment_status" != deploying ] && [ -f "$STATE_FILE" ]; then
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

prepare_blue_green_rollback_target() {
  user_switched=false
  admin_switched=false
  batch_switched=false

  if [ "$current_deployment_status" = deploying ]; then
    [ "$current_target_sha" = "$DEPLOYED_SHA" ] || {
      echo "an interrupted deployment targets $current_target_sha; refusing to overwrite it with $DEPLOYED_SHA" >&2
      return 1
    }

    if [ "$active_user_image" != "$rollback_user_image" ] \
        || [ "$active_user_color" != "$rollback_user_color" ] \
        || [ "$active_user_port" != "$rollback_user_port" ]; then
      user_switched=true
    fi
    if [ "$active_admin_image" != "$rollback_admin_image" ] \
        || [ "$active_admin_color" != "$rollback_admin_color" ] \
        || [ "$active_admin_port" != "$rollback_admin_port" ]; then
      admin_switched=true
    fi
    if [ "$active_batch_image" != "$rollback_batch_image" ]; then
      batch_switched=true
    fi
  else
    rollback_user_color="$active_user_color"
    rollback_user_port="$active_user_port"
    rollback_admin_color="$active_admin_color"
    rollback_admin_port="$active_admin_port"
    rollback_deployed_sha="${current_deployed_sha:-bootstrap}"
  fi

  capture_rollback_images || return 1
  [ -n "$rollback_user_image" ] \
    && [ -n "$rollback_admin_image" ] \
    && [ -n "$rollback_batch_image" ] || {
      echo "cannot deploy without a complete rollback image set" >&2
      return 1
    }
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
      return 1
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
  local exit_code="${1:-$?}"
  trap - ERR

  echo "deploy failed; attempting rollback"

  if ! restore_firebase_credentials; then
    echo "Firebase credential restore failed; continuing image rollback" >&2
  fi

  if ! ensure_user_runtime_env; then
    echo "Firebase runtime env restore failed; continuing image rollback" >&2
  fi

  if [ "$legacy_from_blue_green" = true ]; then
    systemctl stop nginx >/dev/null 2>&1 || true
    stop_slot user-api "$active_user_color"
    stop_slot admin-api "$active_admin_color"
  fi

  if [ -z "$rollback_user_image" ] || [ -z "$rollback_admin_image" ]; then
    # user/admin 이전 이미지가 없어도(완전 신규 박스 최초 배포) 방금 기동한 실패 batch 는
    # user/admin 가용성과 무관하게 정지해야 하므로 rollback_batch 를 먼저 부른다.
    rollback_batch || echo "batch rollback failed; continuing user/admin recovery" >&2
    cleanup_firebase_credentials_backup
    echo "rollback skipped: previous user-api/admin-api images are not available" >&2
    exit "$exit_code"
  fi

  write_units "$rollback_user_image" "$rollback_admin_image" "${rollback_batch_image:-$NEW_BATCH_IMAGE}"

  # batch 는 독립 유닛이라 user-api/admin-api 복구보다 먼저 처리한다 — 아래 user/admin 재기동이
  # set -e 로 실패해 스크립트가 죽더라도 batch 복구가 건너뛰어지지 않도록.
  rollback_batch || echo "batch rollback failed; continuing user/admin recovery" >&2

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

rollback_legacy_dispatch() {
  local exit_code="$?"

  if [ "$legacy_from_blue_green" = true ] && [ "$legacy_cutover_started" = false ]; then
    trap - ERR
    echo "legacy deploy failed before fixed-port cutover; blue/green traffic remains active" >&2
    restore_firebase_credentials || true
    ensure_user_runtime_env || true
    cleanup_firebase_credentials_backup
    exit "$exit_code"
  fi

  rollback "$exit_code"
}

prepare_runtime_configuration() {
  backup_firebase_credentials
  refresh_firebase_credentials

  if ! ensure_user_runtime_env \
      || ! refresh_social_auth_env \
      || ! refresh_webex_alert_env \
      || ! refresh_admin_origin_secret_env \
      || ! refresh_llm_env "$USER_RUNTIME_ENV" \
      || ! refresh_bots_env \
      || ! refresh_market_engine_env; then
    if ! restore_firebase_credentials; then
      echo "Firebase credential restore failed; preserving backup before exit" >&2
    fi
    ensure_user_runtime_env || true
    cleanup_firebase_credentials_backup
    return 1
  fi
}

pull_release_images() {
  aws ecr get-login-password --region "$AWS_REGION" \
    | docker login "$REGISTRY" --username AWS --password-stdin
  docker pull "$NEW_USER_IMAGE"
  docker pull "$NEW_ADMIN_IMAGE"
  docker pull "$NEW_BATCH_IMAGE"
}

finish_successful_deploy() {
  trap - ERR
  firebase_credentials_replaced=false
  cleanup_firebase_credentials_backup
  docker ps
}

deploy_legacy() {
  load_blue_green_state
  if [ -n "$active_user_color" ] || [ -n "$active_admin_color" ]; then
    [ -n "$active_user_color" ] && [ -n "$active_admin_color" ] || {
      echo "cannot enter legacy mode from a partial blue/green state" >&2
      return 1
    }
    legacy_from_blue_green=true
  fi

  capture_rollback_images
  prune_unused_docker_images
  prepare_runtime_configuration
  trap rollback_legacy_dispatch ERR

  pull_release_images
  write_units "$NEW_USER_IMAGE" "$NEW_ADMIN_IMAGE" "$NEW_BATCH_IMAGE"

  # Emergency compatibility path. Normal deployments use deploy_blue_green.
  if [ "$legacy_from_blue_green" = true ]; then
    legacy_cutover_started=true
    systemctl stop nginx
    stop_slot user-api "$active_user_color"
    stop_slot admin-api "$active_admin_color"
  fi
  systemctl restart rougether-user-api rougether-admin-api
  wait_health user-api http://127.0.0.1:8080/api/v1/health
  wait_health admin-api http://127.0.0.1:8081/admin/health

  ensure_batch_runtime_env
  refresh_llm_env "$BATCH_RUNTIME_ENV"
  refresh_batch_webex_alert_env || echo "batch Webex alert env refresh failed; batch alerts stay disabled" >&2
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
  finish_successful_deploy
}

rollback_blue_green() {
  local exit_code="$?"
  local rollback_ok=true
  trap - ERR

  echo "blue/green deploy failed; attempting release-set rollback"
  if ! restore_firebase_credentials; then
    echo "Firebase credential restore failed; continuing traffic rollback" >&2
    rollback_ok=false
  fi
  if ! ensure_user_runtime_env; then
    echo "Firebase runtime env restore failed; continuing traffic rollback" >&2
    rollback_ok=false
  fi

  if [ "$batch_switched" = true ]; then
    rollback_batch || rollback_ok=false
    active_batch_image="$rollback_batch_image"
  fi
  if [ "$admin_switched" = true ]; then
    rollback_api_blue_green admin-api "$rollback_admin_image" \
      "$rollback_admin_color" "$rollback_admin_port" || rollback_ok=false
  fi
  if [ "$user_switched" = true ]; then
    rollback_api_blue_green user-api "$rollback_user_image" \
      "$rollback_user_color" "$rollback_user_port" || rollback_ok=false
  fi

  if [ "$rollback_ok" = true ]; then
    write_blue_green_state ready "${rollback_deployed_sha:-rollback}" || rollback_ok=false
  fi
  cleanup_firebase_credentials_backup

  if [ "$rollback_ok" = true ]; then
    echo "blue/green rollback completed"
  else
    echo "blue/green rollback is incomplete; inspect SSM and systemd logs" >&2
  fi
  exit "$exit_code"
}

deploy_blue_green() {
  load_blue_green_state

  if blue_green_release_is_active; then
    wait_health_stable user-api-current "$(stable_health_url user-api)"
    wait_health_stable admin-api-current "$(stable_health_url admin-api)"
    wait_health batch http://127.0.0.1:8082/actuator/health
    echo "release $DEPLOYED_SHA is already active; deployment is idempotent"
    return 0
  fi

  prepare_blue_green_rollback_target
  prune_unused_docker_images
  prepare_runtime_configuration
  trap rollback_blue_green ERR

  pull_release_images
  write_blue_green_units

  deploy_api_blue_green user-api "$NEW_USER_IMAGE"
  deploy_api_blue_green admin-api "$NEW_ADMIN_IMAGE"

  ensure_batch_runtime_env
  refresh_llm_env "$BATCH_RUNTIME_ENV"
  refresh_batch_webex_alert_env || echo "batch Webex alert env refresh failed; batch alerts stay disabled" >&2
  cat > "$BATCH_DEPLOY_ENV" <<EOF
ROUGETHER_BATCH_IMAGE=$NEW_BATCH_IMAGE
EOF
  chmod 600 "$BATCH_DEPLOY_ENV"
  active_batch_image="$NEW_BATCH_IMAGE"
  batch_switched=true
  write_blue_green_state deploying "${rollback_deployed_sha:-bootstrap}"
  systemctl restart rougether-batch
  wait_health batch http://127.0.0.1:8082/actuator/health

  write_blue_green_state ready "$DEPLOYED_SHA"
  current_deployed_sha="$DEPLOYED_SHA"
  finish_successful_deploy
}

# ---------------------------------------------------------------------------
# 호스트 이벤트 감시(#418): 컨테이너 OOM kill·rougether-* 유닛 비정상 재시작을 운영 Webex 로 알린다.
# 감시 스크립트는 SSM 으로 이 파일 하나만 내려가므로 heredoc 으로 싣고, 배포 때마다 내용이 바뀐 경우에만 교체한다.
# ---------------------------------------------------------------------------
write_container_watch_script() {
  cat <<'ROUGETHER_WATCH_SCRIPT'
#!/usr/bin/env bash
# Generated by deploy-ec2-with-rollback.sh (#418); do not edit manually.
# 1분마다 systemd timer 로 실행된다. 감지 대상:
#  - 실행 중인 rougether-* 컨테이너 cgroup v2 memory.events 의 oom_kill 증가
#  - rougether-* 유닛의 자동 재시작(NRestarts 증가)과 enabled 유닛의 failed 전이
# 배포 중(deploy-in-progress 잠금)에는 알리지 않고 기준값만 갱신한다. 같은 종류·대상은 cooldown 동안 한 번만 알린다.
set -uo pipefail
umask 077

CGROUP_ROOT="${ROUGETHER_WATCH_CGROUP_ROOT:-/sys/fs/cgroup}"
STATE_DIR="${ROUGETHER_WATCH_STATE_DIR:-/var/lib/rougether/watch}"
RUNTIME_ENV="${ROUGETHER_WATCH_RUNTIME_ENV:-/etc/rougether/user-api.env}"
ENVIRONMENT_NAME="${ROUGETHER_WATCH_ENVIRONMENT:-unknown}"
COOLDOWN_SECONDS="${ROUGETHER_WATCH_COOLDOWN_SECONDS:-1800}"
DEPLOY_LOCK_MAX_AGE_SECONDS="${ROUGETHER_WATCH_DEPLOY_LOCK_MAX_AGE_SECONDS:-3600}"
WEBEX_MESSAGES_URL="${ROUGETHER_WATCH_WEBEX_URL:-https://webexapis.com/v1/messages}"
SELF_UNIT="rougether-container-watch.service"
NOW="${ROUGETHER_WATCH_NOW:-$(date +%s)}"
DEPLOY_LOCK="$STATE_DIR/deploy-in-progress"
INITIALIZED_MARKER="$STATE_DIR/initialized"
LAST_RUN_FILE="$STATE_DIR/last-run"
SUPPRESS_ALERTS=false
INITIALIZED=false

log() {
  echo "container-watch: $*" >&2
}

is_number() {
  [[ "${1:-}" =~ ^[0-9]+$ ]]
}

read_number() {
  local value=""
  [ -f "$1" ] && value="$(head -n 1 "$1" 2>/dev/null || true)"
  if is_number "$value"; then
    printf '%s' "$value"
  fi
}

write_state() {
  local destination="$1"
  local value="$2"
  local temporary="$destination.tmp.$$"
  printf '%s\n' "$value" > "$temporary" && mv -f "$temporary" "$destination"
}

format_time() {
  TZ=Asia/Seoul date -d "@$NOW" '+%Y-%m-%d %H:%M:%S KST' 2>/dev/null \
    || TZ=Asia/Seoul date -r "$NOW" '+%Y-%m-%d %H:%M:%S KST' 2>/dev/null \
    || printf '@%s' "$NOW"
}

runtime_env_value() {
  [ -r "$RUNTIME_ENV" ] || return 0
  awk -v key="$1" 'index($0, key "=") == 1 {value = substr($0, length(key) + 2)} END {printf "%s", value}' "$RUNTIME_ENV"
}

deploy_in_progress() {
  local started age
  [ -f "$DEPLOY_LOCK" ] || return 1
  started="$(read_number "$DEPLOY_LOCK")"
  if [ -z "$started" ]; then
    log "ignoring unreadable deploy lock"
    return 1
  fi
  age=$(( NOW - started ))
  if [ "$age" -ge "$DEPLOY_LOCK_MAX_AGE_SECONDS" ]; then
    log "ignoring stale deploy lock (${age}s old)"
    return 1
  fi
  return 0
}

cooldown_file() {
  printf '%s/cooldown/%s__%s' "$STATE_DIR" "$1" "$2"
}

alert_allowed() {
  local last
  last="$(read_number "$(cooldown_file "$1" "$2")")"
  [ -z "$last" ] || [ $(( NOW - last )) -ge "$COOLDOWN_SECONDS" ]
}

# 토큰은 명령행 인자·로그에 남기지 않는다: Authorization 헤더는 0600 임시 파일로 curl 에 넘긴다.
send_webex() {
  local text="$1"
  local token room header_file body_file exit_code=0

  token="$(runtime_env_value OPERATIONS_WEBEX_BOT_TOKEN)"
  room="$(runtime_env_value OPERATIONS_WEBEX_ROOM_ID)"
  if [ -z "$token" ] || [ -z "$room" ] \
      || [[ "$token" == *[[:space:]]* ]] || [[ "$room" == *[[:space:]]* ]]; then
    log "Webex credentials are missing or invalid in $RUNTIME_ENV; alert skipped"
    return 1
  fi

  header_file="$(mktemp "$STATE_DIR/.webex-header.XXXXXX")" || return 1
  body_file="$(mktemp "$STATE_DIR/.webex-body.XXXXXX")" || { rm -f "$header_file"; return 1; }
  printf 'Authorization: Bearer %s\nContent-Type: application/json\n' "$token" > "$header_file"
  if ! printf '%s' "$text" | ROUGETHER_WATCH_ROOM="$room" python3 -c '
import json, os, sys
print(json.dumps({"roomId": os.environ["ROUGETHER_WATCH_ROOM"], "markdown": sys.stdin.read()}))
' > "$body_file"; then
    rm -f "$header_file" "$body_file"
    return 1
  fi

  curl -fsS --connect-timeout 5 --max-time 15 -o /dev/null \
    -H @"$header_file" --data-binary @"$body_file" "$WEBEX_MESSAGES_URL" || exit_code="$?"
  rm -f "$header_file" "$body_file"
  return "$exit_code"
}

notify() {
  local kind="$1"
  local target="$2"
  local title="$3"
  local detail="$4"
  local message

  if [ "$SUPPRESS_ALERTS" = true ]; then
    log "deploy in progress; $kind alert for $target suppressed"
    return 0
  fi
  if ! alert_allowed "$kind" "$target"; then
    log "$kind alert for $target suppressed by cooldown"
    return 0
  fi

  # shellcheck disable=SC2016 # 백틱은 Webex markdown 코드 표기다.
  message="$(printf '**[%s] %s**\n\n- 대상: `%s`\n- 내용: %s\n- 시각: %s' \
    "$ENVIRONMENT_NAME" "$title" "$target" "$detail" "$(format_time)")"
  if send_webex "$message"; then
    write_state "$(cooldown_file "$kind" "$target")" "$NOW"
    log "$kind alert sent for $target"
  else
    log "$kind alert for $target could not be sent"
  fi
}

container_memory_events() {
  local container_id="$1"
  local candidate
  for candidate in \
      "$CGROUP_ROOT/system.slice/docker-$container_id.scope/memory.events" \
      "$CGROUP_ROOT/docker/$container_id/memory.events"; do
    if [ -r "$candidate" ]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  return 1
}

check_container_oom_kills() {
  local line container_id container_name events_file count previous
  local -a seen=()

  mkdir -p "$STATE_DIR/oom" || return 1
  while IFS= read -r line; do
    container_id="${line%% *}"
    container_name="${line#* }"
    [[ "$container_id" =~ ^[0-9a-f]{64}$ ]] || continue
    [[ "$container_name" =~ ^rougether-[A-Za-z0-9_.@-]+$ ]] || continue
    seen+=("$container_id")
    events_file="$(container_memory_events "$container_id")" || continue
    count="$(awk '$1 == "oom_kill" {print $2; exit}' "$events_file")"
    is_number "$count" || continue

    previous="$(read_number "$STATE_DIR/oom/$container_id")"
    if [ -z "$previous" ]; then
      # 감시 첫 실행이면 기존 누적값을 기준으로 삼고, 이후 새로 뜬 컨테이너는 0부터 센다.
      if [ "$INITIALIZED" = true ]; then previous=0; else previous="$count"; fi
    fi
    if [ "$count" -gt "$previous" ]; then
      notify oom "$container_name" "컨테이너 OOM kill 감지" \
        "cgroup memory.events oom_kill +$(( count - previous )) (누적 $count). 상한·힙 설정과 docker logs 를 확인하세요."
    fi
    write_state "$STATE_DIR/oom/$container_id" "$count"
  done < <(docker ps --no-trunc --filter 'name=^rougether-' --format '{{.ID}} {{.Names}}' 2>/dev/null)

  local state_file known found
  for state_file in "$STATE_DIR"/oom/*; do
    [ -f "$state_file" ] || continue
    known="${state_file##*/}"
    found=false
    for container_id in "${seen[@]+"${seen[@]}"}"; do
      [ "$container_id" = "$known" ] && found=true && break
    done
    [ "$found" = true ] || rm -f "$state_file"
  done
}

unit_exit_summary() {
  local unit="$1"
  local since="$2"
  journalctl -u "$unit" --since "@$since" --no-pager -o cat 2>/dev/null \
    | grep -E 'Main process exited|Failed with result' \
    | tail -n 3 \
    | tr '\n' ' ' \
    | tr -d '`*'
}

check_unit_restarts() {
  local unit restarts previous active_state unit_file_state previous_state since summary detail

  mkdir -p "$STATE_DIR/restarts" "$STATE_DIR/unit-state" || return 1
  since="$(read_number "$LAST_RUN_FILE")"
  [ -n "$since" ] || since=$(( NOW - 120 ))

  while IFS= read -r unit; do
    [[ "$unit" =~ ^rougether-[A-Za-z0-9_.@-]+\.service$ ]] || continue
    [ "$unit" != "$SELF_UNIT" ] || continue

    restarts="$(systemctl show -p NRestarts --value "$unit" 2>/dev/null || true)"
    if is_number "$restarts"; then
      previous="$(read_number "$STATE_DIR/restarts/$unit")"
      if [ -z "$previous" ]; then
        if [ "$INITIALIZED" = true ]; then previous=0; else previous="$restarts"; fi
      fi
      # 수동 restart/start 는 NRestarts 를 0으로 되돌리므로 감소는 기준값 갱신만 한다.
      if [ "$restarts" -gt "$previous" ]; then
        summary="$(unit_exit_summary "$unit" "$since")"
        detail="자동 재시작 +$(( restarts - previous )) (누적 $restarts)."
        if printf '%s' "$summary" | grep -Eq 'status=137|status=9/KILL|oom-kill'; then
          detail="$detail OOM kill(exit 137) 추정."
        fi
        [ -z "$summary" ] || detail="$detail 최근 종료: $summary"
        notify restart "$unit" "서비스 비정상 종료·재시작 감지" "$detail"
      fi
      write_state "$STATE_DIR/restarts/$unit" "$restarts"
    fi

    active_state="$(systemctl show -p ActiveState --value "$unit" 2>/dev/null || true)"
    unit_file_state="$(systemctl show -p UnitFileState --value "$unit" 2>/dev/null || true)"
    previous_state="$(head -n 1 "$STATE_DIR/unit-state/$unit" 2>/dev/null || true)"
    # 배포가 disable 후 내린 슬롯도 failed 로 끝날 수 있으므로 enabled 유닛이 failed 로 바뀔 때만 알린다.
    if [ "$active_state" = failed ] && [ "$previous_state" != failed ] \
        && [ "$unit_file_state" = enabled ] && [ "$INITIALIZED" = true ]; then
      summary="$(unit_exit_summary "$unit" "$since")"
      notify failed "$unit" "서비스 중단(failed) 감지" "재시작을 멈추고 failed 상태입니다. ${summary:-journalctl -u $unit 로 확인하세요.}"
    fi
    [ -z "$active_state" ] || write_state "$STATE_DIR/unit-state/$unit" "$active_state"
  done < <(systemctl list-units --all --plain --no-legend --type=service 'rougether-*' 2>/dev/null | awk '{print $1}')
}

main() {
  is_number "$NOW" || { log "invalid clock value"; return 1; }
  mkdir -p "$STATE_DIR/cooldown" || return 1
  chmod 700 "$STATE_DIR" 2>/dev/null || true
  [ -f "$INITIALIZED_MARKER" ] && INITIALIZED=true
  if deploy_in_progress; then
    SUPPRESS_ALERTS=true
  fi

  check_container_oom_kills || log "container OOM check failed"
  check_unit_restarts || log "unit restart check failed"

  write_state "$LAST_RUN_FILE" "$NOW"
  [ "$INITIALIZED" = true ] || : > "$INITIALIZED_MARKER"
}

main "$@"
ROUGETHER_WATCH_SCRIPT
}

watch_environment_label() {
  if [[ "$ENVIRONMENT" =~ ^[A-Za-z0-9._-]{1,64}$ ]]; then
    printf '%s' "$ENVIRONMENT"
  else
    printf 'unknown'
  fi
}

write_container_watch_service() {
  cat <<EOF
[Unit]
Description=Rougether container OOM and restart watch
After=docker.service

[Service]
Type=oneshot
Environment=ROUGETHER_WATCH_ENVIRONMENT=$(watch_environment_label)
Environment=ROUGETHER_WATCH_STATE_DIR=$WATCH_STATE_DIR
Environment=ROUGETHER_WATCH_RUNTIME_ENV=$USER_RUNTIME_ENV
ExecStart=$WATCH_SCRIPT_PATH
EOF
}

write_container_watch_timer() {
  cat <<'EOF'
[Unit]
Description=Run the Rougether container watch every minute

[Timer]
OnBootSec=2min
OnUnitActiveSec=1min
AccuracySec=10s

[Install]
WantedBy=timers.target
EOF
}

# 내용이 같으면 파일을 건드리지 않는다(멱등). 교체했으면 0, 그대로면 1을 돌려준다.
replace_file_if_changed() {
  local destination="$1"
  local mode="$2"
  local writer="$3"
  local temporary

  temporary="$(mktemp "$(dirname "$destination")/.$(basename "$destination").XXXXXX")" || return 2
  if ! "$writer" > "$temporary"; then
    rm -f "$temporary"
    return 2
  fi
  if [ -f "$destination" ] && cmp -s "$temporary" "$destination"; then
    rm -f "$temporary"
    return 1
  fi
  chmod "$mode" "$temporary" || { rm -f "$temporary"; return 2; }
  mv -f "$temporary" "$destination" || return 2
  return 0
}

install_container_watch() {
  local changed=false result path

  for path in "$WATCH_SCRIPT_PATH" "$WATCH_STATE_DIR" "$USER_RUNTIME_ENV"; do
    if ! [[ "$path" =~ ^/[A-Za-z0-9._/-]+$ ]]; then
      echo "invalid container watch path: $path" >&2
      return 1
    fi
  done

  mkdir -p "$(dirname "$WATCH_SCRIPT_PATH")" "$WATCH_STATE_DIR" "$SYSTEMD_DIR" || return 1
  chmod 700 "$WATCH_STATE_DIR" || return 1

  result=0
  replace_file_if_changed "$WATCH_SCRIPT_PATH" 755 write_container_watch_script || result="$?"
  [ "$result" -ne 2 ] || return 1
  [ "$result" -ne 0 ] || changed=true

  result=0
  replace_file_if_changed "$SYSTEMD_DIR/$WATCH_UNIT_NAME.service" 644 write_container_watch_service || result="$?"
  [ "$result" -ne 2 ] || return 1
  [ "$result" -ne 0 ] || changed=true

  result=0
  replace_file_if_changed "$SYSTEMD_DIR/$WATCH_UNIT_NAME.timer" 644 write_container_watch_timer || result="$?"
  [ "$result" -ne 2 ] || return 1
  [ "$result" -ne 0 ] || changed=true

  if [ "$changed" = true ]; then
    systemctl daemon-reload || return 1
    echo "container watch installed or updated"
  fi
  systemctl enable --now "$WATCH_UNIT_NAME.timer" || return 1
}

# 배포 중 수동 재시작·컨테이너 교체를 알림으로 오인하지 않게 감시 스크립트에 잠금을 남긴다.
# 스크립트가 강제 종료돼 잠금이 남아도 감시 스크립트가 1시간 뒤 낡은 잠금으로 보고 무시한다.
begin_deploy_watch_suppression() {
  mkdir -p "$WATCH_STATE_DIR" || return 1
  chmod 700 "$WATCH_STATE_DIR" || return 1
  printf '%s\n' "$(date +%s)" > "$WATCH_STATE_DIR/deploy-in-progress"
}

end_deploy_watch_suppression() {
  rm -f "$WATCH_STATE_DIR/deploy-in-progress"
}

# BEGIN_DEPLOY_EXECUTION
case "$DEPLOY_MODE" in
  hold)
    echo "deployment mode is hold; no EC2 state was changed"
    ;;
  legacy|blue-green)
    # 메모리 설정이 잘못되면 호스트를 건드리기 전에 멈춘다(유닛 작성·롤백 모두 같은 값을 쓴다).
    validate_memory_settings || exit 2
    trap end_deploy_watch_suppression EXIT
    begin_deploy_watch_suppression || echo "cannot mark deployment for the container watch; alerts may fire during deploy" >&2
    install_container_watch || echo "container watch install failed; deployment continues without host alerts" >&2
    if [ "$DEPLOY_MODE" = legacy ]; then
      deploy_legacy
    else
      deploy_blue_green
    fi
    ;;
  *)
    echo "invalid deployment mode: $DEPLOY_MODE (expected hold, legacy, or blue-green)" >&2
    exit 2
    ;;
esac
