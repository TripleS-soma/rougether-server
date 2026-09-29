#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_SCRIPT="$SCRIPT_DIR/deploy-ec2-with-rollback.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/rougether-deploy-test.XXXXXX")"
FUNCTIONS_FILE="$TEST_ROOT/deploy-functions.sh"

cleanup_test_root() {
  find "$TEST_ROOT" -type f -delete 2>/dev/null || true
  find "$TEST_ROOT" -depth -type d -exec rmdir {} \; 2>/dev/null || true
}
trap cleanup_test_root EXIT

# Load function definitions only. The marker precedes the mode dispatcher that mutates the host.
awk '/^# BEGIN_DEPLOY_EXECUTION$/{exit} {print}' "$DEPLOY_SCRIPT" > "$FUNCTIONS_FILE"
source "$FUNCTIONS_FILE"

AWS_MOCK_MODE="fail"
AWS_MOCK_PAYLOAD=""

aws() {
  if [ "$AWS_MOCK_MODE" = "fail" ]; then
    return 1
  fi

  if [ "$AWS_MOCK_MODE" = "social" ]; then
    local parameter_name=""
    while [ "$#" -gt 0 ]; do
      if [ "$1" = "--name" ]; then
        parameter_name="$2"
        break
      fi
      shift
    done
    case "$parameter_name" in
      /test/kakao) printf '%s\n' 'new-kakao-key' ;;
      /test/apple-team) printf '%s\n' 'NEWTEAM' ;;
      /test/apple-key) printf '%s\n' 'NEWKEY' ;;
      /test/apple-private) printf '%s\n' '-----BEGIN PRIVATE KEY-----' 'ABC123' '-----END PRIVATE KEY-----' ;;
      /test/apple-enc) printf '%s\n' 'new-encryption-key' ;;
      *) return 1 ;;
    esac
    return 0
  fi

  printf '%s\n' "$AWS_MOCK_PAYLOAD"
}

systemctl() {
  return 0
}

chown() {
  return 0
}

write_credentials() {
  local path="$1"
  local name="$2"

  printf '{"type":"service_account","project_id":"%s","private_key":"fake-key-%s","client_email":"%s@example.invalid"}\n' \
    "$name" "$name" "$name" > "$path"
  chmod 600 "$path"
}

reset_scenario() {
  local name="$1"

  ENV_DIR="$TEST_ROOT/$name/etc/rougether"
  SYSTEMD_DIR="$TEST_ROOT/$name/systemd"
  STATE_FILE="$ENV_DIR/deploy-state.env"
  USER_DEPLOY_ENV="$ENV_DIR/user-api.deploy.env"
  ADMIN_DEPLOY_ENV="$ENV_DIR/admin-api.deploy.env"
  BATCH_DEPLOY_ENV="$ENV_DIR/batch.deploy.env"
  USER_RUNTIME_ENV="$ENV_DIR/user-api.env"
  ADMIN_RUNTIME_ENV="$ENV_DIR/admin-api.env"
  BATCH_RUNTIME_ENV="$ENV_DIR/batch.env"
  FIREBASE_CREDENTIALS_FILE="$ENV_DIR/firebase-adminsdk.json"
  NGINX_CONFIG_DIR="$TEST_ROOT/$name/nginx"
  NGINX_CONFIG_FILE="$NGINX_CONFIG_DIR/rougether.conf"
  NGINX_BIN="/usr/bin/true"
  rollback_user_image=""
  rollback_admin_image=""
  rollback_batch_image=""
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
  NEW_USER_IMAGE="registry/user:new"
  NEW_ADMIN_IMAGE="registry/admin:new"
  NEW_BATCH_IMAGE="registry/batch:new"
  DEPLOYED_SHA="new-release-sha"
  BLUE_GREEN_DRAIN_SECONDS=0
  firebase_credentials_backup=""
  firebase_credentials_replaced=false
  AWS_MOCK_MODE="fail"
  AWS_MOCK_PAYLOAD=""
  WEBEX_ROOM_ID="test-room-id"
  ENVIRONMENT="dev"
  KAKAO_ADMIN_KEY_PARAMETER_NAME="/test/kakao"
  APPLE_TEAM_ID_PARAMETER_NAME="/test/apple-team"
  APPLE_KEY_ID_PARAMETER_NAME="/test/apple-key"
  APPLE_PRIVATE_KEY_PARAMETER_NAME="/test/apple-private"
  APPLE_REFRESH_TOKEN_ENC_KEY_PARAMETER_NAME="/test/apple-enc"
  ADMIN_ORIGIN_SECRET_PARAMETER_NAME="/test/admin-origin"
  WATCH_SCRIPT_PATH="$TEST_ROOT/$name/libexec/rougether/container-watch.sh"
  WATCH_STATE_DIR="$TEST_ROOT/$name/var/lib/rougether/watch"
  AWS_REGION="ap-northeast-2"
  CW_NAMESPACE="Rougether/Dev"
  CW_AGENT_CTL="$TEST_ROOT/$name/cwagent/bin/amazon-cloudwatch-agent-ctl"
  CW_AGENT_CONFIG_PATH="$TEST_ROOT/$name/cwagent/etc/rougether-agent.json"
  MEMORY_METRICS_SCRIPT_PATH="$TEST_ROOT/$name/libexec/rougether/memory-metrics.sh"
  USER_MEMORY_LIMIT="1280m"
  USER_JAVA_MAX_HEAP="512m"
  ADMIN_MEMORY_LIMIT="768m"
  ADMIN_JAVA_MAX_HEAP="384m"
  BATCH_MEMORY_LIMIT="768m"
  BATCH_JAVA_MAX_HEAP="384m"
  JAVA_RESERVED_CODE_CACHE="128m"
  JAVA_MAX_METASPACE="256m"
  JAVA_MAX_DIRECT_MEMORY="64m"
  JAVA_NMT="off"
  USER_MEMORY_LIMIT_KB=""
  ADMIN_MEMORY_LIMIT_KB=""

  mkdir -p "$ENV_DIR" "$SYSTEMD_DIR" "$NGINX_CONFIG_DIR"
  printf 'SPRING_PROFILES_ACTIVE=mysql\nDB_PASSWORD=fake-db-password\n' > "$USER_RUNTIME_ENV"
  printf 'SPRING_PROFILES_ACTIVE=mysql\nDB_PASSWORD=fake-db-password\n' > "$ADMIN_RUNTIME_ENV"
  chmod 600 "$USER_RUNTIME_ENV"
  chmod 600 "$ADMIN_RUNTIME_ENV"
}

assert_file_equal() {
  local expected="$1"
  local actual="$2"
  local message="$3"

  if ! cmp -s "$expected" "$actual"; then
    echo "not ok - $message" >&2
    return 1
  fi
}

assert_contains() {
  local pattern="$1"
  local path="$2"
  local message="$3"

  if ! grep -q -- "$pattern" "$path"; then
    echo "not ok - $message" >&2
    return 1
  fi
}

assert_not_contains() {
  local pattern="$1"
  local path="$2"
  local message="$3"

  if grep -q -- "$pattern" "$path"; then
    echo "not ok - $message" >&2
    return 1
  fi
}

assert_before() {
  local first_pattern="$1"
  local second_pattern="$2"
  local path="$3"
  local message="$4"
  local first_line second_line

  first_line="$(grep -n -m1 -- "$first_pattern" "$path" | cut -d: -f1 || true)"
  second_line="$(grep -n -m1 -- "$second_pattern" "$path" | cut -d: -f1 || true)"
  if [ -z "$first_line" ] || [ -z "$second_line" ] || [ "$first_line" -ge "$second_line" ]; then
    echo "not ok - $message" >&2
    return 1
  fi
}

write_matching_nginx_config() {
  local user_port="$1"
  local admin_port="$2"
  : > "$NGINX_CONFIG_FILE"
  if [ -n "$user_port" ]; then
    printf 'upstream user { server 127.0.0.1:%s; }\nserver { listen 8080; }\n' \
      "$user_port" >> "$NGINX_CONFIG_FILE"
  fi
  if [ -n "$admin_port" ]; then
    printf 'upstream admin { server 10.0.0.10:%s; }\nserver { listen 8081; }\n' \
      "$admin_port" >> "$NGINX_CONFIG_FILE"
  fi
}

test_prune_preserves_rollback_tags_and_checks_free_space() {
  reset_scenario "image-prune"
  rollback_user_image="registry/user:previous"
  rollback_admin_image="registry/admin:previous"
  rollback_batch_image="registry/batch:previous"
  local docker_calls="$ENV_DIR/docker-calls.log"

  docker() {
    echo "$*" >> "$docker_calls"
    if [ "$1" = "inspect" ]; then
      case "$4" in
        rougether-user-api) echo "sha256:user" ;;
        rougether-admin-api) echo "sha256:admin" ;;
        rougether-batch) echo "sha256:batch" ;;
      esac
    elif [ "$1" = "image" ] && [ "$2" = "inspect" ]; then
      case "$5" in
        registry/user:previous) echo "sha256:user" ;;
        registry/admin:previous) echo "sha256:admin" ;;
        registry/batch:previous) echo "sha256:batch" ;;
      esac
    fi
  }
  df() {
    printf 'Filesystem 1024-blocks Used Available Capacity Mounted on\n'
    printf '/dev/mock 16777216 8388608 8388608 50%% /\n'
  }

  prune_unused_docker_images

  unset -f docker df
  assert_contains '^image prune -a -f$' "$docker_calls" "deploy must prune unused Docker images before pull"
  if [ "$(grep -c '^tag sha256:user registry/user:previous$' "$docker_calls")" -ne 2 ] \
      || [ "$(grep -c '^tag sha256:admin registry/admin:previous$' "$docker_calls")" -ne 2 ] \
      || [ "$(grep -c '^tag sha256:batch registry/batch:previous$' "$docker_calls")" -ne 2 ]; then
    echo "not ok - rollback tags must be protected before and restored after prune" >&2
    return 1
  fi
  echo "ok - image cleanup preserves rollback tags and checks free space"
}

test_prune_fails_when_free_space_is_still_too_low() {
  reset_scenario "image-prune-low-disk"
  rollback_user_image="registry/user:previous"
  rollback_admin_image="registry/admin:previous"
  rollback_batch_image="registry/batch:previous"

  docker() {
    if [ "$1" = "inspect" ]; then
      case "$4" in
        rougether-user-api) echo "sha256:user" ;;
        rougether-admin-api) echo "sha256:admin" ;;
        rougether-batch) echo "sha256:batch" ;;
      esac
    elif [ "$1" = "image" ] && [ "$2" = "inspect" ]; then
      case "$5" in
        registry/user:previous) echo "sha256:user" ;;
        registry/admin:previous) echo "sha256:admin" ;;
        registry/batch:previous) echo "sha256:batch" ;;
      esac
    fi
  }
  df() {
    printf 'Filesystem 1024-blocks Used Available Capacity Mounted on\n'
    printf '/dev/mock 16777216 15728640 1048576 94%% /\n'
  }

  local exit_code=0
  prune_unused_docker_images >/dev/null 2>&1 || exit_code="$?"

  unset -f docker df
  if [ "$exit_code" -eq 0 ]; then
    echo "not ok - deploy must fail before pull when cleanup leaves less than 4 GiB" >&2
    return 1
  fi
  echo "ok - image cleanup fails early when free space is too low"
}

test_ssm_failure_keeps_existing_credentials() {
  reset_scenario "ssm-failure"
  write_credentials "$FIREBASE_CREDENTIALS_FILE" "existing"
  cp "$FIREBASE_CREDENTIALS_FILE" "$ENV_DIR/expected.json"

  backup_firebase_credentials
  refresh_firebase_credentials
  ensure_user_runtime_env

  assert_file_equal "$ENV_DIR/expected.json" "$FIREBASE_CREDENTIALS_FILE" "SSM failure must keep existing credentials"
  assert_contains '^FIREBASE_CREDENTIALS_PATH=' "$USER_RUNTIME_ENV" "existing credentials must stay enabled"
  cleanup_firebase_credentials_backup
  echo "ok - SSM failure keeps existing credentials"
}

test_invalid_ssm_json_keeps_existing_credentials() {
  reset_scenario "invalid-json"
  write_credentials "$FIREBASE_CREDENTIALS_FILE" "existing"
  cp "$FIREBASE_CREDENTIALS_FILE" "$ENV_DIR/expected.json"
  AWS_MOCK_MODE="payload"
  AWS_MOCK_PAYLOAD='{not-json'

  backup_firebase_credentials
  refresh_firebase_credentials

  assert_file_equal "$ENV_DIR/expected.json" "$FIREBASE_CREDENTIALS_FILE" "invalid SSM JSON must not replace existing credentials"
  cleanup_firebase_credentials_backup
  echo "ok - invalid SSM JSON keeps existing credentials"
}

test_webex_alert_refresh_replaces_only_with_valid_values() {
  reset_scenario "webex-alert"
  printf 'OPERATIONS_WEBEX_BOT_TOKEN=old-token\nOPERATIONS_WEBEX_ROOM_ID=old-room-id\nOPERATIONS_DISCORD_WEBHOOK_URL=stale\n' >> "$USER_RUNTIME_ENV"

  AWS_MOCK_MODE="payload"
  AWS_MOCK_PAYLOAD='new-token'
  refresh_webex_alert_env

  assert_contains '^OPERATIONS_WEBEX_BOT_TOKEN=new-token$' \
    "$USER_RUNTIME_ENV" "valid SSM token must replace the runtime value"
  assert_contains '^OPERATIONS_WEBEX_ROOM_ID=test-room-id$' \
    "$USER_RUNTIME_ENV" "configured Webex room ID must replace the runtime value"
  assert_contains '^ROUGETHER_ENVIRONMENT=dev$' "$USER_RUNTIME_ENV" \
    "Webex alerts must include the deployment environment"
  assert_not_contains '^OPERATIONS_DISCORD_WEBHOOK_URL=' "$USER_RUNTIME_ENV" \
    "stale Discord configuration must be removed"
  if [ "$(grep -c '^OPERATIONS_WEBEX_BOT_TOKEN=' "$USER_RUNTIME_ENV")" -ne 1 ]; then
    echo "not ok - Webex bot token runtime value must not be duplicated" >&2
    return 1
  fi

  AWS_MOCK_PAYLOAD='bad token with whitespace'
  WEBEX_ROOM_ID='bad room id'
  refresh_webex_alert_env

  assert_contains '^OPERATIONS_WEBEX_BOT_TOKEN=new-token$' \
    "$USER_RUNTIME_ENV" "invalid SSM token must keep the current runtime value"
  assert_contains '^OPERATIONS_WEBEX_ROOM_ID=test-room-id$' \
    "$USER_RUNTIME_ENV" "invalid room ID must keep the current runtime value"
  assert_not_contains 'bad token' "$USER_RUNTIME_ENV" \
    "invalid SSM token must never enter the runtime env"
  echo "ok - Webex alert refresh accepts only valid token and room values"
}

test_bots_env_refresh_is_idempotent_and_keeps_value_on_invalid_flag() {
  reset_scenario "bots-env-refresh"
  printf 'ROUGETHER_BOTS_ENABLED=false\n' >> "$USER_RUNTIME_ENV"

  BOTS_ENABLED="true"
  refresh_bots_env
  refresh_bots_env

  assert_contains '^ROUGETHER_BOTS_ENABLED=true$' "$USER_RUNTIME_ENV" \
    "bots flag must be written to the user-api runtime env"
  if [ "$(grep -c '^ROUGETHER_BOTS_ENABLED=' "$USER_RUNTIME_ENV")" -ne 1 ]; then
    echo "not ok - bots flag must not be duplicated across deploys" >&2
    return 1
  fi

  BOTS_ENABLED="maybe"
  refresh_bots_env
  assert_contains '^ROUGETHER_BOTS_ENABLED=true$' "$USER_RUNTIME_ENV" \
    "invalid bots flag must keep the current runtime value"
  assert_not_contains 'maybe' "$USER_RUNTIME_ENV" \
    "invalid bots flag must never enter the runtime env"

  BOTS_ENABLED="false"
  refresh_bots_env
  assert_contains '^ROUGETHER_BOTS_ENABLED=false$' "$USER_RUNTIME_ENV" \
    "bots flag can be turned off again"
  echo "ok - bots flag refresh is idempotent and validates the value"
}

test_market_engine_env_refresh_is_idempotent_and_keeps_value_on_invalid_flag() {
  reset_scenario "market-engine-env-refresh"
  printf 'MARKET_ENGINE_ENABLED=false\n' >> "$USER_RUNTIME_ENV"

  MARKET_ENGINE_ENABLED="true"
  refresh_market_engine_env
  refresh_market_engine_env

  assert_contains '^MARKET_ENGINE_ENABLED=true$' "$USER_RUNTIME_ENV" \
    "market engine flag must be written to the user-api runtime env"
  if [ "$(grep -c '^MARKET_ENGINE_ENABLED=' "$USER_RUNTIME_ENV")" -ne 1 ]; then
    echo "not ok - market engine flag must not be duplicated across deploys" >&2
    return 1
  fi

  MARKET_ENGINE_ENABLED="maybe"
  refresh_market_engine_env
  assert_contains '^MARKET_ENGINE_ENABLED=true$' "$USER_RUNTIME_ENV" \
    "invalid market engine flag must keep the current runtime value"
  assert_not_contains 'maybe' "$USER_RUNTIME_ENV" \
    "invalid market engine flag must never enter the runtime env"

  MARKET_ENGINE_ENABLED="false"
  refresh_market_engine_env
  assert_contains '^MARKET_ENGINE_ENABLED=false$' "$USER_RUNTIME_ENV" \
    "market engine flag can be turned off again"
  echo "ok - market engine flag refresh is idempotent and validates the value"
}

test_social_auth_refresh_updates_existing_runtime_env() {
  reset_scenario "social-auth-refresh"
  cat >> "$USER_RUNTIME_ENV" <<'EOF'
ASSET_S3_PURGE_VERSIONS=false
KAKAO_ADMIN_KEY=old-kakao-key
APPLE_TEAM_ID=OLDTEAM
APPLE_KEY_ID=OLDKEY
APPLE_PRIVATE_KEY=old-private-key
APPLE_REFRESH_TOKEN_ENC_KEY=old-encryption-key
EOF
  AWS_MOCK_MODE="social"

  refresh_social_auth_env

  assert_contains '^ASSET_S3_PURGE_VERSIONS=true$' "$USER_RUNTIME_ENV" \
    "managed versioned buckets must enable permanent profile deletion on every deploy"
  assert_contains '^KAKAO_ADMIN_KEY=new-kakao-key$' "$USER_RUNTIME_ENV" \
    "Kakao key must refresh from SSM"
  assert_contains '^APPLE_TEAM_ID=NEWTEAM$' "$USER_RUNTIME_ENV" \
    "Apple team ID must refresh from SSM"
  assert_contains '^APPLE_KEY_ID=NEWKEY$' "$USER_RUNTIME_ENV" \
    "Apple key ID must refresh from SSM"
  assert_contains '^APPLE_PRIVATE_KEY=-----BEGIN PRIVATE KEY-----\\nABC123\\n-----END PRIVATE KEY-----\\n$' \
    "$USER_RUNTIME_ENV" "multiline Apple key must be escaped for Docker env files"
  assert_contains '^APPLE_REFRESH_TOKEN_ENC_KEY=new-encryption-key$' "$USER_RUNTIME_ENV" \
    "Apple refresh-token encryption key must refresh from SSM"
  if [ "$(grep -c '^APPLE_PRIVATE_KEY=' "$USER_RUNTIME_ENV")" -ne 1 ]; then
    echo "not ok - social auth runtime keys must not be duplicated" >&2
    return 1
  fi
  echo "ok - social auth and purge runtime settings refresh on existing instances"
}

test_social_auth_ssm_failure_keeps_existing_values() {
  reset_scenario "social-auth-ssm-failure"
  cat >> "$USER_RUNTIME_ENV" <<'EOF'
KAKAO_ADMIN_KEY=old-kakao-key
APPLE_TEAM_ID=OLDTEAM
APPLE_KEY_ID=OLDKEY
APPLE_PRIVATE_KEY=old-private-key
APPLE_REFRESH_TOKEN_ENC_KEY=old-encryption-key
EOF

  refresh_social_auth_env

  assert_contains '^ASSET_S3_PURGE_VERSIONS=true$' "$USER_RUNTIME_ENV" \
    "purge setting must be added even when optional SSM secrets are unavailable"
  assert_contains '^KAKAO_ADMIN_KEY=old-kakao-key$' "$USER_RUNTIME_ENV" \
    "SSM failure must preserve the existing Kakao key"
  assert_contains '^APPLE_PRIVATE_KEY=old-private-key$' "$USER_RUNTIME_ENV" \
    "SSM failure must preserve the existing Apple private key"
  echo "ok - social auth SSM failure preserves existing runtime values"
}

test_webex_alert_ssm_failure_keeps_existing_token() {
  reset_scenario "webex-alert-ssm-failure"
  printf 'OPERATIONS_WEBEX_BOT_TOKEN=existing-token\nOPERATIONS_WEBEX_ROOM_ID=existing-room-id\n' >> "$USER_RUNTIME_ENV"

  refresh_webex_alert_env

  assert_contains '^OPERATIONS_WEBEX_BOT_TOKEN=existing-token$' \
    "$USER_RUNTIME_ENV" "SSM failure must keep the existing Webex bot token"
  assert_contains '^OPERATIONS_WEBEX_ROOM_ID=test-room-id$' \
    "$USER_RUNTIME_ENV" "configured room ID must still be reconciled"
  assert_contains '^ROUGETHER_ENVIRONMENT=dev$' "$USER_RUNTIME_ENV" \
    "SSM failure must still reconcile the deployment environment"
  echo "ok - Webex token SSM failure keeps existing value"
}

test_first_deploy_without_credentials_uses_stub() {
  reset_scenario "first-deploy-stub"
  printf 'FIREBASE_CREDENTIALS_PATH=/stale/path.json\n' >> "$USER_RUNTIME_ENV"

  refresh_firebase_credentials
  ensure_user_runtime_env
  write_units "user-image" "admin-image" "batch-image"

  assert_not_contains '^FIREBASE_CREDENTIALS_PATH=' "$USER_RUNTIME_ENV" "missing credentials must remove runtime path"
  assert_not_contains 'firebase-adminsdk.json' "$SYSTEMD_DIR/rougether-user-api.service" "missing credentials must omit bind mount"
  assert_contains '--network host' "$SYSTEMD_DIR/rougether-admin-api.service" "admin-api must preserve loopback identity for SSM and local health checks"
  assert_not_contains '-p 8081:8081' "$SYSTEMD_DIR/rougether-admin-api.service" "admin-api host networking must not keep a bridge port mapping"
  assert_contains '127.0.0.1:8082:8082' "$SYSTEMD_DIR/rougether-batch.service" "batch must bind health port to localhost only"
  assert_not_contains 'rougether-user-api.service' "$SYSTEMD_DIR/rougether-batch.service" "batch must not depend on user-api"
  assert_not_contains 'firebase-adminsdk.json' "$SYSTEMD_DIR/rougether-batch.service" "missing credentials must omit batch bind mount"
  echo "ok - first deploy without credentials uses stub"
}

test_new_credentials_are_restored_with_runtime_wiring() {
  reset_scenario "credential-rollback"
  write_credentials "$FIREBASE_CREDENTIALS_FILE" "existing"
  cp "$FIREBASE_CREDENTIALS_FILE" "$ENV_DIR/expected.json"
  AWS_MOCK_MODE="payload"
  AWS_MOCK_PAYLOAD='{"type":"service_account","project_id":"new","private_key":"fake-key-new","client_email":"new@example.invalid"}'

  backup_firebase_credentials
  refresh_firebase_credentials
  ensure_user_runtime_env
  ensure_batch_runtime_env
  write_units "new-user-image" "new-admin-image" "new-batch-image"
  assert_contains '"project_id":"new"' "$FIREBASE_CREDENTIALS_FILE" "new credentials must be installed before restart"
  assert_contains 'firebase-adminsdk.json:ro' "$SYSTEMD_DIR/rougether-batch.service" "valid credentials must mount into batch"
  assert_contains '^FIREBASE_CREDENTIALS_PATH=' "$BATCH_RUNTIME_ENV" "valid credentials must wire batch runtime env"

  restore_firebase_credentials
  ensure_user_runtime_env
  write_units "old-user-image" "old-admin-image" "old-batch-image"

  assert_file_equal "$ENV_DIR/expected.json" "$FIREBASE_CREDENTIALS_FILE" "rollback must restore previous credentials"
  assert_contains '^FIREBASE_CREDENTIALS_PATH=' "$USER_RUNTIME_ENV" "rollback must restore runtime env"
  assert_contains 'firebase-adminsdk.json:ro' "$SYSTEMD_DIR/rougether-user-api.service" "rollback must restore read-only mount"
  cleanup_firebase_credentials_backup
  echo "ok - rollback restores credentials, env, and mount"
}

test_restore_failure_is_propagated_and_backup_is_kept() {
  reset_scenario "restore-failure"
  write_credentials "$FIREBASE_CREDENTIALS_FILE" "existing"
  AWS_MOCK_MODE="payload"
  AWS_MOCK_PAYLOAD='{"type":"service_account","project_id":"new","private_key":"fake-key-new","client_email":"new@example.invalid"}'

  backup_firebase_credentials
  refresh_firebase_credentials
  local backup_path="$firebase_credentials_backup"

  mv() {
    return 1
  }

  if restore_firebase_credentials; then
    echo "not ok - restore failure must be propagated" >&2
    return 1
  fi
  unset -f mv

  if [ "$firebase_credentials_replaced" != true ] || [ ! -f "$backup_path" ]; then
    echo "not ok - failed restore must preserve state and backup" >&2
    return 1
  fi

  cleanup_firebase_credentials_backup
  if [ ! -f "$backup_path" ]; then
    echo "not ok - cleanup must keep backup after incomplete rollback" >&2
    return 1
  fi

  firebase_credentials_replaced=false
  cleanup_firebase_credentials_backup
  echo "ok - restore failure is propagated and backup is kept"
}

test_rollback_restarts_both_services_in_parallel_then_checks_health() {
  reset_scenario "parallel-restart"
  rollback_user_image="old-user-image"
  rollback_admin_image="old-admin-image"
  local call_log="$ENV_DIR/calls.log"

  # systemctl 호출과 health 확인 순서를 기록해 병렬 재시작 계약을 검증한다
  systemctl() {
    echo "systemctl $*" >> "$call_log"
    return 0
  }
  curl() {
    # 마지막 인자가 URL — health 성공 시나리오
    echo "curl ${*: -1}" >> "$call_log"
    return 0
  }

  local exit_code=0
  (false || rollback) > /dev/null 2>&1 || exit_code="$?"
  # unset -f 는 상단의 base mock 까지 지워버린다 — 이후 테스트가 실제 systemctl 을
  # 호출하지 않도록 base mock 으로 되돌린다 (curl 은 base mock 이 없어 제거)
  systemctl() { return 0; }
  unset -f curl

  if [ "$exit_code" -ne 1 ]; then
    echo "not ok - rollback must exit with the original failure code (got $exit_code)" >&2
    return 1
  fi
  assert_contains '^systemctl restart rougether-user-api rougether-admin-api$' "$call_log" \
    "rollback must restart both services in one parallel transaction"
  if [ "$(grep -c '^systemctl restart' "$call_log")" -ne 1 ]; then
    echo "not ok - rollback must not restart services sequentially" >&2
    return 1
  fi
  if ! grep -A2 'restart rougether-user-api rougether-admin-api' "$call_log" \
      | tail -2 | paste -sd' ' - | grep -q '8080/api/v1/health.*8081/admin/health'; then
    echo "not ok - health checks must run after restart, user-api first" >&2
    return 1
  fi
  echo "ok - rollback restarts both services in parallel, then checks health in order"
}

test_rollback_health_failure_is_reported_and_propagated() {
  reset_scenario "health-failure"
  rollback_user_image="old-user-image"
  rollback_admin_image="old-admin-image"
  local output_log="$ENV_DIR/output.log"

  systemctl() { return 0; }
  # admin-api health 만 계속 실패 — user 성공 후 admin 실패가 보고·전파되는지 확인
  curl() {
    case "${*: -1}" in
      *8081*) return 1 ;;
      *) return 0 ;;
    esac
  }
  sleep() { :; }

  local exit_code=0
  # 데드라인을 1초로 줄여 실패 루프를 빠르게 소진시킨다 (sleep 은 mock)
  (false || ROUGETHER_HEALTH_TIMEOUT_SECONDS=1 rollback) > "$output_log" 2>&1 || exit_code="$?"
  # base mock 복원 (위 테스트와 동일한 이유)
  systemctl() { return 0; }
  unset -f curl sleep

  if [ "$exit_code" -eq 0 ]; then
    echo "not ok - admin health failure during rollback must propagate a non-zero exit" >&2
    return 1
  fi
  # exit code 는 원래 실패 코드로 고정이므로, 제어 흐름은 출력으로 검증한다:
  # 실패가 집계되어 'health checks failed' 경고가 나가고 성공 메시지는 나가지 않아야 한다
  assert_contains 'rollback finished but health checks failed' "$output_log" \
    "admin health failure must be reported after rollback"
  assert_not_contains 'rollback completed' "$output_log" \
    "failed rollback must not claim success"
  assert_contains 'user-api health check passed' "$output_log" \
    "user-api health must still be checked and pass"
  echo "ok - rollback health failure is reported and propagated"
}

test_capture_rollback_images_preserves_new_deploy_sha() {
  reset_scenario "deploy-state"
  DEPLOYED_SHA="new-deploy-sha"
  cat > "$STATE_FILE" <<'EOF'
USER_API_IMAGE=old-user-image
ADMIN_API_IMAGE=old-admin-image
BATCH_API_IMAGE=old-batch-image
DEPLOYED_SHA=old-deploy-sha
EOF

  capture_rollback_images

  if [ "$rollback_user_image" != "old-user-image" ] || [ "$rollback_admin_image" != "old-admin-image" ] || [ "$rollback_batch_image" != "old-batch-image" ]; then
    echo "not ok - rollback images must be read from deploy state" >&2
    return 1
  fi

  if [ "$DEPLOYED_SHA" != "new-deploy-sha" ]; then
    echo "not ok - previous deploy state must not overwrite the new deploy SHA" >&2
    return 1
  fi

  echo "ok - rollback state preserves the new deploy SHA"
}

test_batch_env_is_bootstrapped_from_user_runtime_env() {
  reset_scenario "batch-env-bootstrap"
  cat > "$USER_RUNTIME_ENV" <<'EOF'
SPRING_PROFILES_ACTIVE=mysql
SERVER_PORT=8080
DB_URL=jdbc:mysql://db.example.invalid:3306/rougether
DB_USERNAME=rougether
DB_PASSWORD=fake-db-password
JWT_SECRET=fake-jwt-secret
EOF
  chmod 600 "$USER_RUNTIME_ENV"

  ensure_batch_runtime_env

  assert_contains '^SERVER_PORT=8082$' "$BATCH_RUNTIME_ENV" "batch.env must bind port 8082"
  assert_contains '^DB_URL=jdbc:mysql://db.example.invalid:3306/rougether$' "$BATCH_RUNTIME_ENV" "batch.env must copy DB_URL"
  assert_contains '^DB_USERNAME=rougether$' "$BATCH_RUNTIME_ENV" "batch.env must copy DB_USERNAME"
  assert_contains '^DB_PASSWORD=fake-db-password$' "$BATCH_RUNTIME_ENV" "batch.env must copy DB_PASSWORD"
  assert_not_contains '^JWT_SECRET=' "$BATCH_RUNTIME_ENV" "batch.env must not leak unrelated settings"
  assert_not_contains '^FIREBASE_CREDENTIALS_PATH=' "$BATCH_RUNTIME_ENV" "no credentials must omit batch firebase path"
  echo "ok - batch.env is bootstrapped from user runtime env"
}

test_first_batch_deploy_failure_stops_new_batch() {
  reset_scenario "batch-first-deploy-fail"
  rollback_batch_image=""
  local calls_file="$ENV_DIR/systemctl-calls.log"
  : > "$calls_file"

  # systemctl 호출을 기록하는 스파이로 잠깐 대체하고, docker 는 실제 실행을 막는다.
  systemctl() { echo "$*" >> "$calls_file"; return 0; }
  docker() { return 0; }

  rollback_batch

  # 기본 상태로 복구한다.
  systemctl() { return 0; }
  unset -f docker

  assert_contains 'stop rougether-batch' "$calls_file" "first-deploy failure must stop the new batch"
  assert_contains 'disable rougether-batch' "$calls_file" "first-deploy failure must disable the new batch"
  assert_not_contains 'restart rougether-batch' "$calls_file" "no previous image means no restart"
  echo "ok - first batch deploy failure stops the new batch"
}

test_rollback_batch_restores_previous_image_deploy_env() {
  reset_scenario "batch-rollback-image"
  rollback_batch_image="registry/batch:previous"
  # 실패 배포가 새 이미지로 남긴 deploy env 를 흉내낸다.
  printf 'ROUGETHER_BATCH_IMAGE=registry/batch:new-failed\n' > "$BATCH_DEPLOY_ENV"
  chmod 600 "$BATCH_DEPLOY_ENV"
  printf 'DB_URL=x\nDB_USERNAME=u\nDB_PASSWORD=p\n' > "$USER_RUNTIME_ENV"
  chmod 600 "$USER_RUNTIME_ENV"

  # systemctl/wait_health 를 서브셸 안에서만 우회한다(curl 루프 방지).
  ( systemctl() { return 0; }; wait_health() { return 0; }; rollback_batch )

  assert_contains '^ROUGETHER_BATCH_IMAGE=registry/batch:previous$' "$BATCH_DEPLOY_ENV" "rollback must point batch deploy env at the previous image"
  assert_not_contains 'new-failed' "$BATCH_DEPLOY_ENV" "rollback must not keep the failed new image"
  echo "ok - rollback_batch restores the previous batch image"
}

test_rollback_recovers_batch_even_if_user_admin_rollback_fails() {
  reset_scenario "rollback-batch-first"
  rollback_user_image="registry/user:prev"
  rollback_admin_image="registry/admin:prev"
  rollback_batch_image="registry/batch:prev"
  printf 'ROUGETHER_BATCH_IMAGE=registry/batch:new-failed\n' > "$BATCH_DEPLOY_ENV"
  printf 'DB_URL=x\nDB_USERNAME=u\nDB_PASSWORD=p\n' > "$USER_RUNTIME_ENV"
  chmod 600 "$BATCH_DEPLOY_ENV" "$USER_RUNTIME_ENV"

  # user-api health 가 실패해 rollback 이 set -e 로 죽어도 batch 는 이미 이전 이미지로 복구돼 있어야 한다.
  ( systemctl() { return 0; }
    wait_health() { case "$1" in user-api) return 1;; *) return 0;; esac; }
    rollback ) >/dev/null 2>&1 || true

  assert_contains '^ROUGETHER_BATCH_IMAGE=registry/batch:prev$' "$BATCH_DEPLOY_ENV" "batch must be recovered before user/admin rollback can abort"
  echo "ok - rollback recovers batch even if user/admin rollback fails"
}

test_rollback_stops_batch_when_no_user_admin_images() {
  reset_scenario "rollback-no-images"
  rollback_user_image=""
  rollback_admin_image=""
  rollback_batch_image=""
  local calls_file="$ENV_DIR/systemctl-calls.log"
  : > "$calls_file"

  systemctl() { echo "$*" >> "$calls_file"; return 0; }
  docker() { return 0; }

  # rollback() 은 마지막에 exit 를 부르므로 서브셸에서 실행해 테스트 프로세스가 죽지 않게 한다.
  ( rollback ) >/dev/null 2>&1 || true

  systemctl() { return 0; }
  unset -f docker

  assert_contains 'stop rougether-batch' "$calls_file" "rollback must stop failed batch even without user/admin images"
  assert_contains 'disable rougether-batch' "$calls_file" "rollback must disable failed batch even without user/admin images"
  echo "ok - rollback stops batch when user/admin images are unavailable"
}

test_batch_env_wires_firebase_when_credentials_present() {
  reset_scenario "batch-env-firebase"
  cat > "$USER_RUNTIME_ENV" <<'EOF'
SPRING_PROFILES_ACTIVE=mysql
DB_URL=jdbc:mysql://db.example.invalid:3306/rougether
DB_USERNAME=rougether
DB_PASSWORD=fake-db-password
EOF
  chmod 600 "$USER_RUNTIME_ENV"
  write_credentials "$FIREBASE_CREDENTIALS_FILE" "batch"

  ensure_batch_runtime_env
  assert_contains '^FIREBASE_CREDENTIALS_PATH=/etc/rougether/firebase-adminsdk.json$' "$BATCH_RUNTIME_ENV" "valid credentials must wire batch firebase path"

  # 자격증명이 사라지면 다음 배포에서 경로가 제거되어야 한다(재조정 멱등성).
  rm -f "$FIREBASE_CREDENTIALS_FILE"
  ensure_batch_runtime_env
  assert_not_contains '^FIREBASE_CREDENTIALS_PATH=' "$BATCH_RUNTIME_ENV" "removed credentials must drop batch firebase path"
  assert_contains '^DB_URL=' "$BATCH_RUNTIME_ENV" "reconcile must preserve DB settings"
  echo "ok - batch.env firebase path tracks credential validity"
}

test_batch_env_bootstrap_is_idempotent() {
  reset_scenario "batch-env-idempotent"
  printf 'SPRING_PROFILES_ACTIVE=mysql\nSERVER_PORT=8082\nDB_PASSWORD=existing\n' > "$BATCH_RUNTIME_ENV"
  chmod 600 "$BATCH_RUNTIME_ENV"

  ensure_batch_runtime_env

  assert_contains '^DB_PASSWORD=existing$' "$BATCH_RUNTIME_ENV" "existing batch.env must be preserved"
  echo "ok - batch.env bootstrap leaves existing file untouched"
}

test_admin_origin_secret_refresh_is_fail_closed() {
  reset_scenario "admin-origin-secret"
  AWS_MOCK_MODE="payload"
  AWS_MOCK_PAYLOAD="new-origin-secret"

  refresh_admin_origin_secret_env
  assert_contains '^ADMIN_ORIGIN_SECRET=new-origin-secret$' "$ADMIN_RUNTIME_ENV" \
    "valid admin origin secret must be installed in the runtime env"

  AWS_MOCK_MODE="fail"
  if refresh_admin_origin_secret_env; then
    echo "not ok - missing admin origin secret must fail the deployment" >&2
    return 1
  fi
  assert_contains '^ADMIN_ORIGIN_SECRET=new-origin-secret$' "$ADMIN_RUNTIME_ENV" \
    "failed refresh must preserve the last valid admin origin secret"
  echo "ok - admin origin secret refresh is fail-closed"
}

test_blue_green_slot_mapping_and_state_validation() {
  reset_scenario "blue-green-state"
  cat > "$STATE_FILE" <<'EOF'
USER_API_IMAGE=registry/user:old
USER_API_ACTIVE_COLOR=green
USER_API_ACTIVE_PORT=28080
ADMIN_API_IMAGE=registry/admin:old
ADMIN_API_ACTIVE_COLOR=blue
ADMIN_API_ACTIVE_PORT=18081
BATCH_API_IMAGE=registry/batch:old
DEPLOYED_SHA=old-release
EOF
  write_matching_nginx_config 28080 18081

  load_blue_green_state

  [ "$active_user_color" = green ] && [ "$active_user_port" = 28080 ] \
    && [ "$active_admin_color" = blue ] && [ "$active_admin_port" = 18081 ] \
    || { echo "not ok - valid active slot state must load exactly" >&2; return 1; }
  [ "$(inactive_color "$active_user_color")" = blue ] \
    || { echo "not ok - inactive user slot must alternate" >&2; return 1; }

  sed -i.bak 's/ADMIN_API_ACTIVE_PORT=18081/ADMIN_API_ACTIVE_PORT=28081/' "$STATE_FILE"
  active_admin_color=""
  active_admin_port=""
  if load_blue_green_state >/dev/null 2>&1; then
    echo "not ok - mismatched color and port must fail closed" >&2
    return 1
  fi

  cat > "$STATE_FILE" <<EOF
USER_API_IMAGE=$NEW_USER_IMAGE
USER_API_ACTIVE_COLOR=blue
USER_API_ACTIVE_PORT=18080
ADMIN_API_IMAGE=registry/admin:old
ADMIN_API_ACTIVE_COLOR=
ADMIN_API_ACTIVE_PORT=
BATCH_API_IMAGE=registry/batch:old
DEPLOYMENT_STATUS=deploying
DEPLOYED_SHA=old-release
TARGET_SHA=$DEPLOYED_SHA
ROLLBACK_USER_API_IMAGE=registry/user:old
ROLLBACK_USER_API_ACTIVE_COLOR=
ROLLBACK_USER_API_ACTIVE_PORT=
ROLLBACK_ADMIN_API_IMAGE=registry/admin:old
ROLLBACK_ADMIN_API_ACTIVE_COLOR=
ROLLBACK_ADMIN_API_ACTIVE_PORT=
ROLLBACK_BATCH_API_IMAGE=registry/batch:old
ROLLBACK_DEPLOYED_SHA=old-release
EOF
  write_matching_nginx_config 18080 ""
  load_blue_green_state || {
    echo "not ok - first user cutover checkpoint must be recoverable before admin cutover" >&2
    return 1
  }
  echo "ok - blue/green state validates color and port pairs"
}

test_legacy_state_tolerates_stopped_nginx_leftover_and_retires_it() {
  reset_scenario "legacy-nginx-leftover"
  cat > "$STATE_FILE" <<'EOF'
USER_API_IMAGE=registry/user:old
ADMIN_API_IMAGE=registry/admin:old
BATCH_API_IMAGE=registry/batch:old
DEPLOYED_SHA=old-release
EOF
  # 실제 rougether.conf 처럼 listen 이 줄 머리에 오는 고정 포트 설정(blue/green 시절에 쓰인 것)
  printf 'server {\n    listen 8080;\n}\nserver {\n    listen 8081;\n}\n' > "$NGINX_CONFIG_FILE"
  local calls_file="$TEST_ROOT/legacy-nginx-leftover/systemctl.log"
  : > "$calls_file"

  # nginx 가 멈춰 있으면 legacy 고정 포트와 충돌하지 않으므로 남은 설정이 있어도 상태를 읽는다.
  if ! ( systemctl() { [ "$1" = is-active ] && return 3; return 0; }; load_blue_green_state ) >/dev/null 2>&1; then
    echo "not ok - stopped nginx with a leftover fixed-port config must not block legacy state" >&2
    return 1
  fi
  # nginx 가 떠 있으면 실제 포트 충돌이므로 계속 막는다.
  if ( systemctl() { return 0; }; load_blue_green_state ) >/dev/null 2>&1; then
    echo "not ok - running nginx that owns a fixed port without an active slot must fail closed" >&2
    return 1
  fi

  ( systemctl() { echo "$*" >> "$calls_file"; return 0; }; retire_nginx_routing )
  assert_contains '^disable --now nginx$' "$calls_file" "legacy must disable nginx so it cannot grab 8080 after reboot"
  [ ! -e "$NGINX_CONFIG_FILE" ] && [ -f "$NGINX_CONFIG_FILE.legacy-disabled" ] \
    || { echo "not ok - leftover nginx config must move out of conf.d includes" >&2; return 1; }
  ( systemctl() { return 0; }; load_blue_green_state ) >/dev/null 2>&1 \
    || { echo "not ok - retired config must not block a later deploy even if nginx runs" >&2; return 1; }
  echo "ok - legacy tolerates a stopped nginx leftover and retires it"
}

test_blue_green_units_bind_internal_ports_and_cap_memory() {
  reset_scenario "blue-green-units"
  write_blue_green_units

  assert_contains '127.0.0.1:${ROUGETHER_HOST_PORT}:8080' \
    "$SYSTEMD_DIR/rougether-user-api@.service" \
    "user slots must bind only their loopback candidate port"
  assert_contains '--memory 1280m --memory-swap 1280m' \
    "$SYSTEMD_DIR/rougether-user-api@.service" \
    "user candidate must have a hard Docker memory cap without swap allowance"
  assert_contains "\"JAVA_TOOL_OPTIONS=$(expected_java_options 512m)\"" \
    "$SYSTEMD_DIR/rougether-user-api@.service" \
    "user JVM heap and off-heap areas must stay below its container cap"
  assert_contains '--network host --memory 768m --memory-swap 768m' \
    "$SYSTEMD_DIR/rougether-admin-api@.service" \
    "admin slots must preserve host-network origin verification and cap memory"
  assert_contains "\"JAVA_TOOL_OPTIONS=$(expected_java_options 384m)\"" \
    "$SYSTEMD_DIR/rougether-admin-api@.service" \
    "admin slots must carry the shared JVM options"
  assert_contains '--env-file /etc/rougether/admin-api.env --env SERVER_PORT=${ROUGETHER_HOST_PORT}' \
    "$SYSTEMD_DIR/rougether-admin-api@.service" \
    "admin slot port must override the legacy runtime env value"
  assert_contains '--memory 768m --memory-swap 768m' \
    "$SYSTEMD_DIR/rougether-batch.service" \
    "single batch container must also have a hard memory cap"
  assert_contains "\"JAVA_TOOL_OPTIONS=$(expected_java_options 384m)\"" \
    "$SYSTEMD_DIR/rougether-batch.service" \
    "batch must carry the shared JVM options"
  assert_single_java_options_argument "$SYSTEMD_DIR/rougether-user-api@.service" "$(expected_java_options 512m)"
  assert_single_java_options_argument "$SYSTEMD_DIR/rougether-admin-api@.service" "$(expected_java_options 384m)"
  echo "ok - blue/green units isolate candidate ports and cap memory"
}

test_memory_preflight_failure_does_not_start_candidate() {
  reset_scenario "blue-green-memory"
  local meminfo="$ENV_DIR/meminfo"
  local calls="$ENV_DIR/calls.log"
  cat > "$meminfo" <<'EOF'
MemAvailable:    1310720 kB
SwapTotal:       2097148 kB
SwapFree:        2097148 kB
EOF

  local exit_code=0
  ( ROUGETHER_MEMINFO_PATH="$meminfo"
    systemctl() { echo "systemctl $*" >> "$calls"; return 0; }
    docker() { echo "docker $*" >> "$calls"; return 0; }
    start_candidate user-api blue registry/user:new
  ) >/dev/null 2>&1 || exit_code="$?"

  if [ "$exit_code" -eq 0 ]; then
    echo "not ok - insufficient MemAvailable must reject the candidate" >&2
    return 1
  fi
  if [ -f "$calls" ]; then
    assert_not_contains 'systemctl start' "$calls" "memory rejection must not start systemd candidate"
    assert_not_contains 'docker rm' "$calls" "memory rejection must not touch candidate container"
  fi
  echo "ok - memory preflight failure leaves the active service untouched"
}

test_candidate_health_precedes_initial_proxy_cutover() {
  reset_scenario "blue-green-order"
  local calls="$ENV_DIR/calls.log"

  ( start_candidate() {
      echo "candidate-start $1 $2" >> "$calls"
      echo "candidate-healthy $1 $2" >> "$calls"
    }
    apply_proxy_ports() { echo "proxy-switch user=$1 admin=$2" >> "$calls"; }
    wait_health_stable() { echo "stable-health $1" >> "$calls"; }
    write_blue_green_state() { echo "state $1" >> "$calls"; }
    stop_slot() { echo "stop-slot $1 $2" >> "$calls"; }
    systemctl() { echo "systemctl $*" >> "$calls"; return 0; }
    sleep() { :; }
    deploy_api_blue_green user-api registry/user:new
  )

  assert_before 'candidate-healthy user-api blue' 'systemctl stop rougether-user-api' "$calls" \
    "candidate must be healthy before the legacy service releases port 8080"
  assert_before 'systemctl stop rougether-user-api' 'proxy-switch user=18080' "$calls" \
    "initial proxy switch must happen only after fixed-port handoff"
  assert_before 'proxy-switch user=18080' 'stable-health user-api-stable' "$calls" \
    "stable health must run after the atomic proxy switch"
  echo "ok - candidate health precedes initial proxy cutover"
}

test_candidate_health_failure_never_stops_active_service() {
  reset_scenario "blue-green-candidate-fail"
  local calls="$ENV_DIR/calls.log"

  local exit_code=0
  ( start_candidate() { echo "candidate-failed" >> "$calls"; return 1; }
    apply_proxy_ports() { echo "proxy-switch" >> "$calls"; }
    systemctl() { echo "systemctl $*" >> "$calls"; return 0; }
    deploy_api_blue_green user-api registry/user:new
  ) >/dev/null 2>&1 || exit_code="$?"

  if [ "$exit_code" -eq 0 ]; then
    echo "not ok - candidate health failure must fail deployment" >&2
    return 1
  fi
  assert_not_contains 'systemctl stop rougether-user-api' "$calls" \
    "failed candidate must not stop the active legacy service"
  assert_not_contains 'proxy-switch' "$calls" \
    "failed candidate must not change the proxy upstream"
  echo "ok - candidate failure leaves active traffic unchanged"
}

test_nginx_config_preserves_websocket_upgrade() {
  reset_scenario "blue-green-websocket"
  ( ROUGETHER_PRIVATE_IP=10.0.1.10
    render_nginx_config "$NGINX_CONFIG_FILE" 28080 18081
  )
  assert_contains 'server 127.0.0.1:28080;' "$NGINX_CONFIG_FILE" "user upstream must use the candidate slot"
  assert_contains 'location = /api/v1/chat/ws {' "$NGINX_CONFIG_FILE" "chat must have a dedicated WebSocket route"
  assert_contains 'proxy_set_header Upgrade $http_upgrade;' "$NGINX_CONFIG_FILE" "WebSocket upgrade header must remain literal"
  assert_contains 'proxy_set_header Connection "upgrade";' "$NGINX_CONFIG_FILE" "WebSocket connection must be upgraded"
  assert_contains 'proxy_set_header Connection "";' "$NGINX_CONFIG_FILE" "ordinary HTTP routes must retain keepalive behavior"
  assert_contains 'server 10.0.1.10:18081;' "$NGINX_CONFIG_FILE" "admin upstream must remain on its active slot"
  echo "ok - deployed Nginx config preserves WebSocket and ordinary HTTP routing"
}

test_nginx_reload_failure_restores_previous_config() {
  reset_scenario "blue-green-nginx-rollback"
  local expected="$ENV_DIR/expected-nginx.conf"
  printf '%s\n' 'previous upstream' > "$NGINX_CONFIG_FILE"
  cp "$NGINX_CONFIG_FILE" "$expected"

  local exit_code=0
  ( ensure_nginx_installed() { :; }
    render_nginx_config() { printf 'candidate upstream %s %s\n' "$2" "$3" > "$1"; }
    systemctl() {
      case "$1 $2" in
        'is-active --quiet') return 0 ;;
        'reload nginx') return 1 ;;
      esac
      return 0
    }
    apply_proxy_ports 18080 18081
  ) >/dev/null 2>&1 || exit_code="$?"

  if [ "$exit_code" -eq 0 ]; then
    echo "not ok - failed nginx reload must fail the switch" >&2
    return 1
  fi
  assert_file_equal "$expected" "$NGINX_CONFIG_FILE" \
    "failed nginx reload must atomically restore the previous config"
  echo "ok - nginx reload failure restores the previous upstream"
}

test_blue_green_rollback_restarts_previous_slot_before_switching_back() {
  reset_scenario "blue-green-service-rollback"
  local calls="$ENV_DIR/calls.log"
  active_user_color=green
  active_user_port=28080
  active_user_image=registry/user:failed
  active_admin_port=18081

  ( start_candidate() { echo "start-previous $1 $2 $3" >> "$calls"; }
    apply_proxy_ports() { echo "proxy user=$1 admin=$2" >> "$calls"; }
    wait_health_stable() { echo "stable $1" >> "$calls"; }
    stop_slot() { echo "stop-failed $1 $2" >> "$calls"; }
    rollback_api_blue_green user-api registry/user:previous blue 18080
  )

  assert_before 'start-previous user-api blue registry/user:previous' \
    'proxy user=18080 admin=18081' "$calls" \
    "previous slot must be healthy before traffic rolls back"
  assert_before 'proxy user=18080 admin=18081' 'stable user-api-rollback' "$calls" \
    "rollback must verify the fixed endpoint after switching back"
  assert_before 'stable user-api-rollback' 'stop-failed user-api green' "$calls" \
    "failed slot must stay available until rollback traffic is healthy"
  echo "ok - blue/green rollback switches back before stopping the failed slot"
}

test_blue_green_orchestration_is_sequential_and_restarts_batch_once() {
  reset_scenario "blue-green-orchestration"
  local calls="$ENV_DIR/calls.log"

  ( load_blue_green_state() { :; }
    blue_green_release_is_active() { return 1; }
    capture_rollback_images() {
      rollback_user_image=registry/user:old
      rollback_admin_image=registry/admin:old
      rollback_batch_image=registry/batch:old
    }
    prune_unused_docker_images() { :; }
    prepare_runtime_configuration() { :; }
    pull_release_images() { :; }
    write_blue_green_units() { :; }
    deploy_api_blue_green() { echo "deploy-api $1" >> "$calls"; }
    ensure_batch_runtime_env() { :; }
    refresh_llm_env() { :; }
    refresh_batch_webex_alert_env() { :; }
    write_blue_green_state() { echo "state $1 $2" >> "$calls"; }
    systemctl() { echo "systemctl $*" >> "$calls"; }
    wait_health() { echo "health $1" >> "$calls"; }
    finish_successful_deploy() { :; }
    deploy_blue_green
  )

  assert_before 'deploy-api user-api' 'deploy-api admin-api' "$calls" \
    "user candidate transaction must finish before admin candidate starts"
  assert_before 'deploy-api admin-api' 'systemctl restart rougether-batch' "$calls" \
    "batch must restart only after both API switches"
  if [ "$(grep -c '^systemctl restart rougether-batch$' "$calls")" -ne 1 ]; then
    echo "not ok - batch must restart exactly once" >&2
    return 1
  fi
  assert_contains '^state ready new-release-sha$' "$calls" \
    "successful release set must finish with one ready state"
  echo "ok - blue/green APIs are sequential and batch restarts once"
}

test_interrupted_deploy_retry_preserves_stable_release_snapshot() {
  reset_scenario "blue-green-interrupted-retry"
  cat > "$STATE_FILE" <<EOF
USER_API_IMAGE=$NEW_USER_IMAGE
USER_API_ACTIVE_COLOR=green
USER_API_ACTIVE_PORT=28080
ADMIN_API_IMAGE=registry/admin:old
ADMIN_API_ACTIVE_COLOR=blue
ADMIN_API_ACTIVE_PORT=18081
BATCH_API_IMAGE=registry/batch:old
DEPLOYMENT_STATUS=deploying
DEPLOYED_SHA=old-release
TARGET_SHA=$DEPLOYED_SHA
ROLLBACK_USER_API_IMAGE=registry/user:old
ROLLBACK_USER_API_ACTIVE_COLOR=blue
ROLLBACK_USER_API_ACTIVE_PORT=18080
ROLLBACK_ADMIN_API_IMAGE=registry/admin:old
ROLLBACK_ADMIN_API_ACTIVE_COLOR=blue
ROLLBACK_ADMIN_API_ACTIVE_PORT=18081
ROLLBACK_BATCH_API_IMAGE=registry/batch:old
ROLLBACK_DEPLOYED_SHA=old-release
EOF
  write_matching_nginx_config 28080 18081

  load_blue_green_state
  prepare_blue_green_rollback_target

  [ "$rollback_user_image" = "registry/user:old" ] \
    && [ "$rollback_user_color" = blue ] \
    && [ "$rollback_user_port" = 18080 ] \
    && [ "$rollback_admin_image" = "registry/admin:old" ] \
    && [ "$rollback_batch_image" = "registry/batch:old" ] \
    && [ "$rollback_deployed_sha" = old-release ] || {
      echo "not ok - retry must retain the previous complete release as rollback target" >&2
      return 1
    }
  [ "$user_switched" = true ] \
    && [ "$admin_switched" = false ] \
    && [ "$batch_switched" = false ] || {
      echo "not ok - retry must recover components switched by the interrupted attempt" >&2
      return 1
    }

  local rollback_calls="$ENV_DIR/rollback-calls.log"
  local rollback_exit_code=0
  set +e
  ( set +e
    restore_firebase_credentials() { :; }
    ensure_user_runtime_env() { :; }
    rollback_api_blue_green() { echo "$*" >> "$rollback_calls"; }
    write_blue_green_state() { :; }
    cleanup_firebase_credentials_backup() { :; }
    false
    rollback_blue_green
  ) >/dev/null 2>&1
  rollback_exit_code="$?"
  set -e
  [ "$rollback_exit_code" -eq 1 ] \
    && grep -q '^user-api registry/user:old blue 18080$' "$rollback_calls" || {
      echo "not ok - retry failure must roll user-api back to the previous stable slot" >&2
      return 1
    }

  write_blue_green_state deploying old-release
  load_blue_green_state
  [ "$rollback_user_image" = "registry/user:old" ] \
    && [ "$rollback_admin_image" = "registry/admin:old" ] \
    && [ "$rollback_batch_image" = "registry/batch:old" ] || {
      echo "not ok - deploying checkpoints must preserve the stable release snapshot" >&2
      return 1
    }
  echo "ok - interrupted retry preserves the stable release snapshot"
}

test_interrupted_deploy_rejects_a_different_target_sha() {
  reset_scenario "blue-green-interrupted-other-target"
  cat > "$STATE_FILE" <<'EOF'
USER_API_IMAGE=registry/user:partial
USER_API_ACTIVE_COLOR=green
USER_API_ACTIVE_PORT=28080
ADMIN_API_IMAGE=registry/admin:old
ADMIN_API_ACTIVE_COLOR=blue
ADMIN_API_ACTIVE_PORT=18081
BATCH_API_IMAGE=registry/batch:old
DEPLOYMENT_STATUS=deploying
DEPLOYED_SHA=old-release
TARGET_SHA=interrupted-release
ROLLBACK_USER_API_IMAGE=registry/user:old
ROLLBACK_USER_API_ACTIVE_COLOR=blue
ROLLBACK_USER_API_ACTIVE_PORT=18080
ROLLBACK_ADMIN_API_IMAGE=registry/admin:old
ROLLBACK_ADMIN_API_ACTIVE_COLOR=blue
ROLLBACK_ADMIN_API_ACTIVE_PORT=18081
ROLLBACK_BATCH_API_IMAGE=registry/batch:old
ROLLBACK_DEPLOYED_SHA=old-release
EOF
  write_matching_nginx_config 28080 18081
  load_blue_green_state

  if prepare_blue_green_rollback_target >/dev/null 2>&1; then
    echo "not ok - a new release must not overwrite another interrupted deployment" >&2
    return 1
  fi
  echo "ok - interrupted deploy rejects a different target SHA"
}

test_active_release_is_idempotent() {
  reset_scenario "blue-green-idempotent"
  local calls="$ENV_DIR/calls.log"
  cat > "$STATE_FILE" <<EOF
USER_API_IMAGE=$NEW_USER_IMAGE
USER_API_ACTIVE_COLOR=blue
USER_API_ACTIVE_PORT=18080
ADMIN_API_IMAGE=$NEW_ADMIN_IMAGE
ADMIN_API_ACTIVE_COLOR=green
ADMIN_API_ACTIVE_PORT=28081
BATCH_API_IMAGE=$NEW_BATCH_IMAGE
DEPLOYED_SHA=$DEPLOYED_SHA
EOF
  write_matching_nginx_config 18080 28081

  ( wait_health_stable() { echo "stable $1" >> "$calls"; }
    wait_health() { echo "health $1" >> "$calls"; }
    capture_rollback_images() { echo "unexpected mutation" >> "$calls"; return 1; }
    deploy_blue_green
  ) >/dev/null

  assert_not_contains 'unexpected mutation' "$calls" \
    "already-active SHA must not pull, switch, or rewrite deployment state"
  assert_contains '^stable user-api-current$' "$calls" "idempotent deploy must verify user health"
  assert_contains '^stable admin-api-current$' "$calls" "idempotent deploy must verify admin health"
  assert_contains '^health batch$' "$calls" "idempotent deploy must verify batch health"
  echo "ok - already active release is idempotent"
}

test_legacy_emergency_mode_releases_nginx_ports_before_restart() {
  reset_scenario "legacy-from-blue-green"
  local calls="$ENV_DIR/calls.log"
  cat > "$STATE_FILE" <<'EOF'
USER_API_IMAGE=registry/user:old
USER_API_ACTIVE_COLOR=blue
USER_API_ACTIVE_PORT=18080
ADMIN_API_IMAGE=registry/admin:old
ADMIN_API_ACTIVE_COLOR=green
ADMIN_API_ACTIVE_PORT=28081
BATCH_API_IMAGE=registry/batch:old
DEPLOYED_SHA=old-release
EOF
  write_matching_nginx_config 18080 28081

  ( capture_rollback_images() { :; }
    prune_unused_docker_images() { :; }
    prepare_runtime_configuration() { :; }
    pull_release_images() { :; }
    write_units() { :; }
    stop_slot() { echo "stop-slot $1 $2" >> "$calls"; }
    systemctl() { echo "systemctl $*" >> "$calls"; }
    wait_health() { :; }
    ensure_batch_runtime_env() { :; }
    refresh_llm_env() { :; }
    refresh_batch_webex_alert_env() { :; }
    finish_successful_deploy() { trap - ERR; }
    deploy_legacy
  )

  assert_before 'systemctl disable --now nginx' 'systemctl restart rougether-user-api rougether-admin-api' \
    "$calls" "legacy mode must release Nginx fixed ports before starting legacy containers"
  assert_before 'stop-slot user-api blue' 'systemctl restart rougether-user-api rougether-admin-api' \
    "$calls" "legacy mode must stop active user slot before fixed-port restart"
  assert_before 'stop-slot admin-api green' 'systemctl restart rougether-user-api rougether-admin-api' \
    "$calls" "legacy mode must stop active admin slot before fixed-port restart"
  echo "ok - legacy emergency mode safely releases blue/green routing first"
}

expected_java_options() {
  printf -- '-Xmx%s -XX:ReservedCodeCacheSize=128m -XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=64m -XX:+ExitOnOutOfMemoryError' "$1"
}

# systemd 는 ExecStart 를 큰따옴표 인용 규칙으로 인자 분리한다. 공백이 든 JAVA_TOOL_OPTIONS 가 한 인자로 남는지 확인한다.
assert_single_java_options_argument() {
  local unit_file="$1"
  local expected_options="$2"

  if ! python3 - "$unit_file" "$expected_options" <<'PY'
import shlex
import sys

unit_file, expected = sys.argv[1], sys.argv[2]
with open(unit_file, encoding="utf-8") as handle:
    exec_start = next(line for line in handle if line.startswith("ExecStart="))
arguments = shlex.split(exec_start[len("ExecStart="):].strip())
index = arguments.index("--env", arguments.index("--memory-swap"))
raise SystemExit(0 if arguments[index + 1] == "JAVA_TOOL_OPTIONS=" + expected else 1)
PY
  then
    echo "not ok - JAVA_TOOL_OPTIONS must be one quoted ExecStart argument in $unit_file" >&2
    return 1
  fi
}

test_legacy_units_cap_memory_and_pass_jvm_options() {
  reset_scenario "legacy-units-memory"

  write_units "registry/user:new" "registry/admin:new" "registry/batch:new"

  assert_contains '--memory 1280m --memory-swap 1280m' "$SYSTEMD_DIR/rougether-user-api.service" \
    "legacy user-api must have the same hard memory cap as blue/green"
  assert_contains "\"JAVA_TOOL_OPTIONS=$(expected_java_options 512m)\"" "$SYSTEMD_DIR/rougether-user-api.service" \
    "legacy user-api must carry the shared JVM options"
  assert_contains '--network host --memory 768m --memory-swap 768m' "$SYSTEMD_DIR/rougether-admin-api.service" \
    "legacy admin-api must have a hard memory cap"
  assert_contains "\"JAVA_TOOL_OPTIONS=$(expected_java_options 384m)\"" "$SYSTEMD_DIR/rougether-admin-api.service" \
    "legacy admin-api must carry the shared JVM options"
  assert_contains '${ROUGETHER_ADMIN_API_IMAGE}' "$SYSTEMD_DIR/rougether-admin-api.service" \
    "legacy admin-api image must stay a systemd variable"
  assert_contains '--memory 768m --memory-swap 768m' "$SYSTEMD_DIR/rougether-batch.service" \
    "legacy batch must have a hard memory cap"
  assert_contains "\"JAVA_TOOL_OPTIONS=$(expected_java_options 384m)\"" "$SYSTEMD_DIR/rougether-batch.service" \
    "legacy batch must carry the shared JVM options"
  assert_single_java_options_argument "$SYSTEMD_DIR/rougether-user-api.service" "$(expected_java_options 512m)"
  assert_single_java_options_argument "$SYSTEMD_DIR/rougether-admin-api.service" "$(expected_java_options 384m)"
  assert_single_java_options_argument "$SYSTEMD_DIR/rougether-batch.service" "$(expected_java_options 384m)"
  assert_not_contains 'JAVA_TOOL_OPTIONS' "$USER_RUNTIME_ENV" \
    "JVM options must not be written into the secret runtime env file"

  if [ "$(memory_limit_kb admin-api)" != 786432 ] || [ "$(memory_limit_kb user-api)" != 1310720 ]; then
    echo "not ok - memory preflight must derive KiB from the container caps" >&2
    return 1
  fi
  if [ "$(ADMIN_MEMORY_LIMIT_KB=700000 memory_limit_kb admin-api)" != 700000 ]; then
    echo "not ok - explicit KiB override must still win" >&2
    return 1
  fi
  echo "ok - legacy units cap memory and pass JVM options like blue/green"
}

test_invalid_memory_override_is_rejected_before_writing_units() {
  reset_scenario "invalid-memory-override"
  ADMIN_JAVA_MAX_HEAP='384m -XX:+Bad'

  if write_units "registry/user:new" "registry/admin:new" "registry/batch:new" >/dev/null 2>&1; then
    echo "not ok - an invalid heap override must be rejected" >&2
    return 1
  fi
  if write_blue_green_units >/dev/null 2>&1; then
    echo "not ok - blue/green units must reject an invalid heap override" >&2
    return 1
  fi
  if [ -f "$SYSTEMD_DIR/rougether-admin-api.service" ] || [ -f "$SYSTEMD_DIR/rougether-admin-api@.service" ]; then
    echo "not ok - invalid overrides must not produce unit files" >&2
    return 1
  fi
  echo "ok - invalid memory overrides are rejected before units are written"
}

test_batch_webex_alert_env_is_idempotent_and_keeps_value_on_invalid_source() {
  reset_scenario "batch-webex-env"
  printf 'SPRING_PROFILES_ACTIVE=mysql\nDB_PASSWORD=fake-db-password\nOPERATIONS_WEBEX_BOT_TOKEN=old-batch-token\n' > "$BATCH_RUNTIME_ENV"
  chmod 600 "$BATCH_RUNTIME_ENV"
  printf 'OPERATIONS_WEBEX_BOT_TOKEN=user-token\nOPERATIONS_WEBEX_ROOM_ID=user-room\nROUGETHER_ENVIRONMENT=dev\n' >> "$USER_RUNTIME_ENV"

  refresh_batch_webex_alert_env
  cp "$BATCH_RUNTIME_ENV" "$ENV_DIR/batch-after-first"
  refresh_batch_webex_alert_env

  assert_file_equal "$ENV_DIR/batch-after-first" "$BATCH_RUNTIME_ENV" \
    "repeated batch Webex refresh must not change the file"
  assert_contains '^OPERATIONS_WEBEX_BOT_TOKEN=user-token$' "$BATCH_RUNTIME_ENV" \
    "batch must receive the user-api Webex token"
  assert_contains '^OPERATIONS_WEBEX_ROOM_ID=user-room$' "$BATCH_RUNTIME_ENV" \
    "batch must receive the user-api Webex room"
  assert_contains '^ROUGETHER_ENVIRONMENT=dev$' "$BATCH_RUNTIME_ENV" \
    "batch alerts must know the deployment environment"
  assert_contains '^DB_PASSWORD=fake-db-password$' "$BATCH_RUNTIME_ENV" \
    "batch secrets must be preserved"
  if [ "$(grep -c '^OPERATIONS_WEBEX_BOT_TOKEN=' "$BATCH_RUNTIME_ENV")" -ne 1 ] \
      || [ "$(grep -c '^$' "$BATCH_RUNTIME_ENV")" -ne 0 ]; then
    echo "not ok - batch Webex values must not be duplicated or padded across deploys" >&2
    return 1
  fi

  printf 'SPRING_PROFILES_ACTIVE=mysql\nOPERATIONS_WEBEX_BOT_TOKEN=bad token\nOPERATIONS_WEBEX_ROOM_ID=\n' > "$USER_RUNTIME_ENV"
  refresh_batch_webex_alert_env 2>/dev/null
  assert_contains '^OPERATIONS_WEBEX_BOT_TOKEN=user-token$' "$BATCH_RUNTIME_ENV" \
    "invalid source token must keep the current batch token"
  assert_contains '^OPERATIONS_WEBEX_ROOM_ID=user-room$' "$BATCH_RUNTIME_ENV" \
    "empty source room must keep the current batch room"
  assert_not_contains 'bad token' "$BATCH_RUNTIME_ENV" \
    "invalid token must never enter the batch runtime env"
  echo "ok - batch Webex alert env is idempotent and keeps values on invalid source"
}

test_container_watch_install_is_idempotent() {
  reset_scenario "watch-install"
  local calls="$ENV_DIR/systemctl-calls.log"
  systemctl() { echo "$*" >> "$calls"; return 0; }

  install_container_watch >/dev/null
  local script_copy="$ENV_DIR/watch-script-copy"
  cp "$WATCH_SCRIPT_PATH" "$script_copy"
  : > "$calls"
  install_container_watch >/dev/null
  systemctl() { return 0; }

  [ -x "$WATCH_SCRIPT_PATH" ] || { echo "not ok - watch script must be executable" >&2; return 1; }
  assert_file_equal "$script_copy" "$WATCH_SCRIPT_PATH" "reinstall must keep the same watch script"
  bash -n "$WATCH_SCRIPT_PATH" || { echo "not ok - installed watch script must be valid bash" >&2; return 1; }
  assert_not_contains 'daemon-reload' "$calls" "unchanged watch units must not trigger daemon-reload"
  assert_contains '^enable --now rougether-container-watch.timer$' "$calls" \
    "every deploy must make sure the watch timer is enabled"
  assert_contains '^OnUnitActiveSec=1min$' "$SYSTEMD_DIR/rougether-container-watch.timer" \
    "watch must run every minute"
  assert_contains "^ExecStart=$WATCH_SCRIPT_PATH$" "$SYSTEMD_DIR/rougether-container-watch.service" \
    "watch service must run the installed script"
  assert_contains '^TimeoutStartSec=50$' "$SYSTEMD_DIR/rougether-container-watch.service" \
    "a hung watch run must be stopped before it blocks the timer"
  assert_contains '^Environment=ROUGETHER_WATCH_ENVIRONMENT=dev$' "$SYSTEMD_DIR/rougether-container-watch.service" \
    "watch alerts must carry the environment name"
  assert_not_contains 'OPERATIONS_WEBEX_BOT_TOKEN=' "$SYSTEMD_DIR/rougether-container-watch.service" \
    "watch unit must not embed the Webex token"
  if [ -n "$(find "$(dirname "$WATCH_SCRIPT_PATH")" "$SYSTEMD_DIR" -name '.*' -type f)" ]; then
    echo "not ok - idempotent install must not leave temporary files" >&2
    return 1
  fi

  printf '# locally modified\n' >> "$WATCH_SCRIPT_PATH"
  : > "$calls"
  systemctl() { echo "$*" >> "$calls"; return 0; }
  install_container_watch >/dev/null
  systemctl() { return 0; }
  assert_file_equal "$script_copy" "$WATCH_SCRIPT_PATH" "drifted watch script must be restored"
  assert_contains '^daemon-reload$' "$calls" "changed watch files must reload systemd"
  echo "ok - container watch install is idempotent"
}

# 감시 스크립트는 별도 프로세스로 돈다. docker/systemctl/journalctl/curl 은 PATH 앞의 가짜 명령으로 대체한다.
setup_watch_fixture() {
  local name="$1"
  reset_scenario "$name"
  systemctl() { return 0; }
  install_container_watch >/dev/null

  WATCH_FAKE_DIR="$TEST_ROOT/$name/fake"
  WATCH_FAKE_BIN="$WATCH_FAKE_DIR/bin"
  WATCH_CGROUP_ROOT="$TEST_ROOT/$name/cgroup"
  mkdir -p "$WATCH_FAKE_BIN" "$WATCH_FAKE_DIR/units" "$WATCH_CGROUP_ROOT/system.slice"
  : > "$WATCH_FAKE_DIR/containers"
  : > "$WATCH_FAKE_DIR/unit-list"
  : > "$WATCH_FAKE_DIR/journal"
  : > "$WATCH_FAKE_DIR/curl-calls.log"
  printf 'OPERATIONS_WEBEX_BOT_TOKEN=watch-secret-token\nOPERATIONS_WEBEX_ROOM_ID=watch-room\n' >> "$USER_RUNTIME_ENV"

  cat > "$WATCH_FAKE_BIN/docker" <<'EOF'
#!/usr/bin/env bash
[ -f "$WATCH_FAKE_DIR/docker-fail" ] && exit 1
[ "$1" = ps ] && cat "$WATCH_FAKE_DIR/containers"
EOF
  cat > "$WATCH_FAKE_BIN/systemctl" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  list-units) cat "$WATCH_FAKE_DIR/unit-list" ;;
  show) cat "$WATCH_FAKE_DIR/units/$5.$3" 2>/dev/null ;;
esac
EOF
  cat > "$WATCH_FAKE_BIN/journalctl" <<'EOF'
#!/usr/bin/env bash
cat "$WATCH_FAKE_DIR/journal"
EOF
  # 인자는 그대로 기록하고, -H @file 로 받은 헤더는 따로 보관해 토큰이 인자로 새지 않았는지 확인한다.
  cat > "$WATCH_FAKE_BIN/curl" <<'EOF'
#!/usr/bin/env bash
if [ -f "$WATCH_FAKE_DIR/curl-fail" ]; then
  echo "FAILED $*" >> "$WATCH_FAKE_DIR/curl-failures.log"
  exit 22
fi
echo "ARGS $*" >> "$WATCH_FAKE_DIR/curl-calls.log"
while [ "$#" -gt 0 ]; do
  case "$1" in
    -H) case "$2" in @*) cat "${2#@}" >> "$WATCH_FAKE_DIR/curl-headers.log" ;; esac; shift ;;
    --data-binary) case "$2" in @*) cat "${2#@}" >> "$WATCH_FAKE_DIR/curl-bodies.log"; echo >> "$WATCH_FAKE_DIR/curl-bodies.log" ;; esac; shift ;;
  esac
  shift
done
exit 0
EOF
  chmod 755 "$WATCH_FAKE_BIN"/*
}

run_watch() {
  local now="$1"
  WATCH_FAKE_DIR="$WATCH_FAKE_DIR" \
    PATH="$WATCH_FAKE_BIN:$PATH" \
    ROUGETHER_WATCH_NOW="$now" \
    ROUGETHER_WATCH_CGROUP_ROOT="$WATCH_CGROUP_ROOT" \
    ROUGETHER_WATCH_STATE_DIR="$WATCH_STATE_DIR" \
    ROUGETHER_WATCH_RUNTIME_ENV="$USER_RUNTIME_ENV" \
    ROUGETHER_WATCH_ENVIRONMENT=dev \
    ROUGETHER_WATCH_COOLDOWN_SECONDS=1800 \
    bash "$WATCH_SCRIPT_PATH" 2>> "$WATCH_FAKE_DIR/watch.log"
}

watch_alert_count() {
  grep -c '^ARGS ' "$WATCH_FAKE_DIR/curl-calls.log" || true
}

set_oom_kill_count() {
  local container_id="$1"
  local count="$2"
  mkdir -p "$WATCH_CGROUP_ROOT/system.slice/docker-$container_id.scope"
  printf 'low 0\nhigh 0\nmax 12\noom 3\noom_kill %s\noom_group_kill 0\n' "$count" \
    > "$WATCH_CGROUP_ROOT/system.slice/docker-$container_id.scope/memory.events"
}

test_container_watch_detects_oom_kill_increase_with_cooldown() {
  setup_watch_fixture "watch-oom"
  local batch_id
  batch_id="$(printf 'b%.0s' $(seq 1 64))"
  printf '%s rougether-batch\n' "$batch_id" > "$WATCH_FAKE_DIR/containers"
  set_oom_kill_count "$batch_id" 2

  run_watch 1000000
  if [ "$(watch_alert_count)" -ne 0 ]; then
    echo "not ok - first watch run must record existing OOM counts as a baseline" >&2
    return 1
  fi

  set_oom_kill_count "$batch_id" 3
  run_watch 1000060
  if [ "$(watch_alert_count)" -ne 1 ]; then
    echo "not ok - oom_kill increase must send one Webex alert" >&2
    return 1
  fi
  assert_contains 'rougether-batch' "$WATCH_FAKE_DIR/curl-bodies.log" "OOM alert must name the container"
  assert_contains '\[dev\]' "$WATCH_FAKE_DIR/curl-bodies.log" "OOM alert must name the environment"
  assert_contains 'KST' "$WATCH_FAKE_DIR/curl-bodies.log" "OOM alert must include the time"
  assert_contains '"roomId": "watch-room"' "$WATCH_FAKE_DIR/curl-bodies.log" "OOM alert must target the operations room"
  assert_contains '^Authorization: Bearer watch-secret-token$' "$WATCH_FAKE_DIR/curl-headers.log" \
    "Webex token must be sent through the header file"
  assert_not_contains 'watch-secret-token' "$WATCH_FAKE_DIR/curl-calls.log" \
    "Webex token must never appear in curl arguments"
  assert_not_contains 'watch-secret-token' "$WATCH_FAKE_DIR/watch.log" \
    "Webex token must never appear in watch logs"

  set_oom_kill_count "$batch_id" 4
  run_watch 1000120
  if [ "$(watch_alert_count)" -ne 1 ]; then
    echo "not ok - repeated OOM kill within cooldown must not alert again" >&2
    return 1
  fi

  set_oom_kill_count "$batch_id" 5
  run_watch 1001900
  if [ "$(watch_alert_count)" -ne 2 ]; then
    echo "not ok - OOM kill after cooldown must alert again" >&2
    return 1
  fi

  # 재기동으로 새 id 가 뜨면 새 컨테이너는 0부터 세고, 사라진 컨테이너 상태는 정리한다.
  local new_id
  new_id="$(printf 'c%.0s' $(seq 1 64))"
  printf '%s rougether-batch\n' "$new_id" > "$WATCH_FAKE_DIR/containers"
  set_oom_kill_count "$new_id" 0
  run_watch 1001960
  if [ -f "$WATCH_STATE_DIR/oom/$batch_id" ] || [ ! -f "$WATCH_STATE_DIR/oom/$new_id" ]; then
    echo "not ok - watch state must follow the running container ids" >&2
    return 1
  fi
  if [ -n "$(find "$WATCH_STATE_DIR" -name '.webex-*' -type f)" ]; then
    echo "not ok - Webex header and body temp files must be removed" >&2
    return 1
  fi
  echo "ok - container watch detects oom_kill increases with cooldown"
}

test_container_watch_alerts_restarts_but_not_during_deploy() {
  setup_watch_fixture "watch-restart"
  printf 'rougether-batch.service loaded active running Rougether batch container\n' > "$WATCH_FAKE_DIR/unit-list"
  printf 'rougether-container-watch.service loaded inactive dead watch\n' >> "$WATCH_FAKE_DIR/unit-list"
  printf 'active\n' > "$WATCH_FAKE_DIR/units/rougether-batch.service.ActiveState"
  printf 'enabled\n' > "$WATCH_FAKE_DIR/units/rougether-batch.service.UnitFileState"
  printf '0\n' > "$WATCH_FAKE_DIR/units/rougether-batch.service.NRestarts"
  printf '7\n' > "$WATCH_FAKE_DIR/units/rougether-container-watch.service.NRestarts"

  run_watch 2000000
  printf '1\n' > "$WATCH_FAKE_DIR/units/rougether-batch.service.NRestarts"
  printf 'rougether-batch.service: Main process exited, code=exited, status=137/n/a\nrougether-batch.service: Failed with result '"'"'exit-code'"'"'.\n' \
    > "$WATCH_FAKE_DIR/journal"
  run_watch 2000060

  if [ "$(watch_alert_count)" -ne 1 ]; then
    echo "not ok - an automatic restart must send exactly one alert" >&2
    return 1
  fi
  assert_contains 'rougether-batch.service' "$WATCH_FAKE_DIR/curl-bodies.log" "restart alert must name the unit"
  assert_contains 'status=137' "$WATCH_FAKE_DIR/curl-bodies.log" "restart alert must include the exit status"
  assert_contains 'OOM kill(exit 137)' "$WATCH_FAKE_DIR/curl-bodies.log" "exit 137 must be flagged as a likely OOM kill"
  assert_not_contains 'rougether-container-watch' "$WATCH_FAKE_DIR/curl-bodies.log" "watch must ignore its own unit"

  # 배포 중에는 재시작·실패를 알리지 않고 기준값만 갱신한다.
  begin_deploy_watch_suppression
  printf '%s\n' 2000100 > "$WATCH_STATE_DIR/deploy-in-progress"
  printf '4\n' > "$WATCH_FAKE_DIR/units/rougether-batch.service.NRestarts"
  run_watch 2003000
  if [ "$(watch_alert_count)" -ne 1 ]; then
    echo "not ok - restarts during a deploy must not alert" >&2
    return 1
  fi
  end_deploy_watch_suppression
  run_watch 2003060
  if [ "$(watch_alert_count)" -ne 1 ]; then
    echo "not ok - deploy-time restarts must not alert after the deploy finishes" >&2
    return 1
  fi

  # 수동 restart 로 카운터가 0이 되면 알리지 않는다.
  printf '0\n' > "$WATCH_FAKE_DIR/units/rougether-batch.service.NRestarts"
  run_watch 2003120
  if [ "$(watch_alert_count)" -ne 1 ]; then
    echo "not ok - a restart counter reset must not alert" >&2
    return 1
  fi

  # 낡은 배포 잠금(1시간 초과)은 무시한다.
  printf '%s\n' 2000000 > "$WATCH_STATE_DIR/deploy-in-progress"
  printf '1\n' > "$WATCH_FAKE_DIR/units/rougether-batch.service.NRestarts"
  run_watch 2010000
  if [ "$(watch_alert_count)" -ne 2 ]; then
    echo "not ok - a stale deploy lock must not silence alerts" >&2
    return 1
  fi

  # enabled 유닛이 failed 로 바뀌면 한 번 알린다(disabled 슬롯의 failed 는 무시).
  printf 'failed\n' > "$WATCH_FAKE_DIR/units/rougether-batch.service.ActiveState"
  run_watch 2010060
  run_watch 2010120
  if [ "$(grep -c 'failed' "$WATCH_FAKE_DIR/curl-bodies.log")" -lt 1 ] || [ "$(watch_alert_count)" -ne 3 ]; then
    echo "not ok - an enabled unit entering failed must alert once" >&2
    return 1
  fi
  echo "ok - container watch alerts restarts but stays quiet during deploys"
}

test_jvm_options_toggle_nmt_and_reject_heap_at_limit() {
  reset_scenario "jvm-options"

  if [ "$(java_tool_options 384m)" != "$(expected_java_options 384m)" ]; then
    echo "not ok - NativeMemoryTracking must be off by default" >&2
    return 1
  fi
  JAVA_NMT="summary"
  if [ "$(java_tool_options 384m)" != "$(expected_java_options 384m) -XX:NativeMemoryTracking=summary" ]; then
    echo "not ok - ROUGETHER_JAVA_NMT=summary must enable NativeMemoryTracking" >&2
    return 1
  fi
  JAVA_NMT="detail"
  if validate_memory_settings >/dev/null 2>&1; then
    echo "not ok - unsupported NMT modes must be rejected" >&2
    return 1
  fi
  JAVA_NMT="off"

  JAVA_MAX_DIRECT_MEMORY="64 m"
  if validate_memory_settings >/dev/null 2>&1; then
    echo "not ok - malformed direct memory override must be rejected" >&2
    return 1
  fi
  JAVA_MAX_DIRECT_MEMORY="64m"

  BATCH_JAVA_MAX_HEAP="768m"
  if validate_memory_settings >/dev/null 2>&1; then
    echo "not ok - a heap equal to the container limit must be rejected" >&2
    return 1
  fi
  BATCH_JAVA_MAX_HEAP="1g"
  if validate_memory_settings >/dev/null 2>&1; then
    echo "not ok - a heap above the container limit must be rejected" >&2
    return 1
  fi
  BATCH_JAVA_MAX_HEAP="384m"
  validate_memory_settings || { echo "not ok - default settings must validate" >&2; return 1; }
  echo "ok - JVM options toggle NMT, cap direct memory, and reject heaps at the limit"
}

test_units_treat_docker_stop_as_success() {
  reset_scenario "unit-success-exit"
  write_units "registry/user:new" "registry/admin:new" "registry/batch:new"
  write_blue_green_units

  local unit
  for unit in rougether-user-api.service rougether-admin-api.service rougether-batch.service \
      rougether-user-api@.service rougether-admin-api@.service; do
    assert_contains '^SuccessExitStatus=143$' "$SYSTEMD_DIR/$unit" \
      "$unit must treat docker stop (exit 143) as a clean stop"
  done
  echo "ok - legacy and blue/green units treat docker stop as success"
}

test_batch_webex_alert_env_failure_keeps_current_file() {
  reset_scenario "batch-webex-env-failure"
  printf 'DB_PASSWORD=fake-db-password\nOPERATIONS_WEBEX_BOT_TOKEN=old-batch-token\n' > "$BATCH_RUNTIME_ENV"
  chmod 600 "$BATCH_RUNTIME_ENV"
  cp "$BATCH_RUNTIME_ENV" "$ENV_DIR/batch-before"
  printf 'OPERATIONS_WEBEX_BOT_TOKEN=user-token\nOPERATIONS_WEBEX_ROOM_ID=user-room\n' >> "$USER_RUNTIME_ENV"

  local exit_code=0
  ( chmod() { return 1; }
    refresh_batch_webex_alert_env
  ) >/dev/null 2>&1 || exit_code="$?"

  if [ "$exit_code" -eq 0 ]; then
    echo "not ok - a failed step must make the batch Webex refresh fail" >&2
    return 1
  fi
  assert_file_equal "$ENV_DIR/batch-before" "$BATCH_RUNTIME_ENV" \
    "a failed batch Webex refresh must not replace the runtime env"
  if [ -n "$(find "$ENV_DIR" -name '.batch.env.*' -o -name '.webex-bot-token.*')" ]; then
    echo "not ok - a failed batch Webex refresh must remove its temporary files" >&2
    return 1
  fi
  echo "ok - batch Webex refresh failure keeps the current runtime env"
}

test_deploy_lock_is_written_atomically() {
  reset_scenario "deploy-lock"
  begin_deploy_watch_suppression

  if ! [[ "$(cat "$WATCH_STATE_DIR/deploy-in-progress")" =~ ^[0-9]+$ ]]; then
    echo "not ok - deploy lock must hold the start epoch" >&2
    return 1
  fi
  if [ -n "$(find "$WATCH_STATE_DIR" -name '.deploy-in-progress.*')" ]; then
    echo "not ok - deploy lock must be moved into place from a temporary file" >&2
    return 1
  fi
  end_deploy_watch_suppression
  [ ! -f "$WATCH_STATE_DIR/deploy-in-progress" ] || { echo "not ok - deploy lock must be removed" >&2; return 1; }
  echo "ok - deploy lock is written atomically and removed"
}

test_container_watch_retries_after_send_failure() {
  setup_watch_fixture "watch-retry"
  local batch_id
  batch_id="$(printf 'd%.0s' $(seq 1 64))"
  printf '%s rougether-batch\n' "$batch_id" > "$WATCH_FAKE_DIR/containers"
  set_oom_kill_count "$batch_id" 0
  printf 'rougether-batch.service loaded active running batch\n' > "$WATCH_FAKE_DIR/unit-list"
  printf '0\n' > "$WATCH_FAKE_DIR/units/rougether-batch.service.NRestarts"
  run_watch 3000000

  : > "$WATCH_FAKE_DIR/curl-fail"
  set_oom_kill_count "$batch_id" 1
  printf '1\n' > "$WATCH_FAKE_DIR/units/rougether-batch.service.NRestarts"
  run_watch 3000060
  if [ "$(watch_alert_count)" -ne 0 ] || [ "$(grep -c '^FAILED ' "$WATCH_FAKE_DIR/curl-failures.log")" -ne 2 ]; then
    echo "not ok - both alerts must be attempted while Webex is failing" >&2
    return 1
  fi
  if [ "$(cat "$WATCH_STATE_DIR/oom/$batch_id")" != 0 ] \
      || [ "$(cat "$WATCH_STATE_DIR/restarts/rougether-batch.service")" != 0 ]; then
    echo "not ok - a failed send must keep the previous baselines" >&2
    return 1
  fi

  rm -f "$WATCH_FAKE_DIR/curl-fail"
  run_watch 3000120
  if [ "$(watch_alert_count)" -ne 2 ]; then
    echo "not ok - failed alerts must be retried on the next run" >&2
    return 1
  fi
  if [ "$(cat "$WATCH_STATE_DIR/oom/$batch_id")" != 1 ] \
      || [ "$(cat "$WATCH_STATE_DIR/restarts/rougether-batch.service")" != 1 ]; then
    echo "not ok - baselines must advance once the alert is sent" >&2
    return 1
  fi
  run_watch 3000180
  if [ "$(watch_alert_count)" -ne 2 ]; then
    echo "not ok - a delivered alert must not repeat" >&2
    return 1
  fi
  echo "ok - container watch retries alerts after a send failure"
}

test_container_watch_keeps_oom_state_when_docker_ps_fails() {
  setup_watch_fixture "watch-docker-fail"
  local batch_id
  batch_id="$(printf 'e%.0s' $(seq 1 64))"
  printf '%s rougether-batch\n' "$batch_id" > "$WATCH_FAKE_DIR/containers"
  set_oom_kill_count "$batch_id" 2
  run_watch 4000000

  : > "$WATCH_FAKE_DIR/docker-fail"
  run_watch 4000060
  if [ "$(cat "$WATCH_STATE_DIR/oom/$batch_id" 2>/dev/null)" != 2 ]; then
    echo "not ok - docker ps failure must not delete OOM state" >&2
    return 1
  fi
  assert_contains 'docker ps failed' "$WATCH_FAKE_DIR/watch.log" "docker ps failure must be logged"

  rm -f "$WATCH_FAKE_DIR/docker-fail"
  set_oom_kill_count "$batch_id" 3
  run_watch 4000120
  if [ "$(watch_alert_count)" -ne 1 ]; then
    echo "not ok - OOM detection must resume against the kept baseline" >&2
    return 1
  fi
  echo "ok - container watch keeps OOM state when docker ps fails"
}

test_container_watch_alerts_unit_failed_during_deploy_after_unlock() {
  setup_watch_fixture "watch-failed-under-lock"
  printf 'rougether-batch.service loaded active running batch\n' > "$WATCH_FAKE_DIR/unit-list"
  printf 'active\n' > "$WATCH_FAKE_DIR/units/rougether-batch.service.ActiveState"
  printf 'enabled\n' > "$WATCH_FAKE_DIR/units/rougether-batch.service.UnitFileState"
  printf '0\n' > "$WATCH_FAKE_DIR/units/rougether-batch.service.NRestarts"
  run_watch 5000000

  printf '%s\n' 5000030 > "$WATCH_STATE_DIR/deploy-in-progress"
  printf 'failed\n' > "$WATCH_FAKE_DIR/units/rougether-batch.service.ActiveState"
  run_watch 5000060
  if [ "$(watch_alert_count)" -ne 0 ]; then
    echo "not ok - a unit failing during a deploy must not alert while locked" >&2
    return 1
  fi

  rm -f "$WATCH_STATE_DIR/deploy-in-progress"
  run_watch 5000120
  run_watch 5000180
  if [ "$(watch_alert_count)" -ne 1 ]; then
    echo "not ok - a unit still failed after the deploy must alert exactly once" >&2
    return 1
  fi
  assert_contains 'failed' "$WATCH_FAKE_DIR/curl-bodies.log" "failed alert must say the unit failed"
  echo "ok - unit failures during a deploy alert once after unlock"
}

# 디스패치 블록은 placeholder 를 치환한 전체 스크립트를 가짜 호스트 명령과 함께 실행해 검증한다.
setup_dispatch_fixture() {
  local name="$1"
  local deploy_mode="$2"

  DISPATCH_DIR="$TEST_ROOT/$name"
  DISPATCH_BIN="$DISPATCH_DIR/bin"
  DISPATCH_LOG="$DISPATCH_DIR/host-calls.log"
  mkdir -p "$DISPATCH_BIN"
  : > "$DISPATCH_LOG"
  sed -e "s|__DEPLOY_MODE__|$deploy_mode|g" -e 's|__ENVIRONMENT__|dev|g' \
    -e 's|__[A-Z_]*__|test-value|g' "$DEPLOY_SCRIPT" > "$DISPATCH_DIR/deploy.sh"

  local command_name
  for command_name in systemctl docker aws curl dnf nginx; do
    cat > "$DISPATCH_BIN/$command_name" <<EOF
#!/usr/bin/env bash
echo "$command_name \$*" >> "$DISPATCH_LOG"
case "$command_name \$1" in
  "systemctl enable") exit 1 ;;
  "systemctl daemon-reload") exit 0 ;;
esac
[ "$command_name" = systemctl ] && exit 0
exit 1
EOF
  done
  # 첫 호스트 변경 직전(디스크 확인)에서 실패를 주입하고, 그 시점에 배포 잠금이 있었는지 기록한다.
  cat > "$DISPATCH_BIN/df" <<EOF
#!/usr/bin/env bash
[ -f "$DISPATCH_DIR/watch/deploy-in-progress" ] && echo "lock-present-during-deploy" >> "$DISPATCH_LOG"
echo "df \$*" >> "$DISPATCH_LOG"
printf 'Filesystem 1024-blocks Used Available\n/dev/mock broken broken broken\n'
EOF
  chmod 755 "$DISPATCH_BIN"/*
}

run_dispatch() {
  PATH="$DISPATCH_BIN:$PATH" \
    ROUGETHER_SYSTEMD_DIR="$DISPATCH_DIR/systemd" \
    ROUGETHER_WATCH_SCRIPT_PATH="$DISPATCH_DIR/libexec/container-watch.sh" \
    ROUGETHER_WATCH_STATE_DIR="$DISPATCH_DIR/watch" \
    bash "$DISPATCH_DIR/deploy.sh" > "$DISPATCH_DIR/output.log" 2>&1
}

test_dispatcher_rejects_invalid_override_before_host_changes() {
  setup_dispatch_fixture "dispatch-invalid-override" legacy

  local exit_code=0
  ROUGETHER_ADMIN_JAVA_MAX_HEAP='384m -XX:+Bad' run_dispatch || exit_code="$?"

  if [ "$exit_code" -ne 2 ]; then
    echo "not ok - invalid memory override must exit 2 (got $exit_code)" >&2
    return 1
  fi
  if [ -s "$DISPATCH_LOG" ] || [ -e "$DISPATCH_DIR/watch" ] || [ -e "$DISPATCH_DIR/systemd" ]; then
    echo "not ok - invalid memory override must stop before touching the host" >&2
    return 1
  fi
  assert_contains 'invalid memory setting' "$DISPATCH_DIR/output.log" "invalid override must be explained"
  echo "ok - dispatcher rejects invalid overrides before host changes"
}

test_dispatcher_removes_deploy_lock_and_preserves_failure_code() {
  setup_dispatch_fixture "dispatch-failure" legacy

  local exit_code=0
  run_dispatch || exit_code="$?"

  if [ "$exit_code" -ne 1 ]; then
    echo "not ok - a failed deploy must exit with the failing step's code (got $exit_code)" >&2
    return 1
  fi
  assert_contains 'container watch install failed; deployment continues' "$DISPATCH_DIR/output.log" \
    "watch install failure must not stop the deployment"
  assert_contains 'cannot determine available disk space' "$DISPATCH_DIR/output.log" \
    "deployment must continue past the watch install to the injected failure"
  assert_contains '^lock-present-during-deploy$' "$DISPATCH_LOG" \
    "deploy lock must exist while the deployment runs"
  if [ -e "$DISPATCH_DIR/watch/deploy-in-progress" ]; then
    echo "not ok - EXIT trap must remove the deploy lock after a failure" >&2
    return 1
  fi
  [ -x "$DISPATCH_DIR/libexec/container-watch.sh" ] \
    || { echo "not ok - watch script must be installed before the deploy runs" >&2; return 1; }
  echo "ok - dispatcher removes the deploy lock and preserves the failure code"
}

# CloudWatch Agent(#419): rpm/dnf/timeout/systemctl 은 셸 함수로, agent-ctl 은 가짜 실행 파일로 대체한다.
setup_cloudwatch_agent_fixture() {
  reset_scenario "$1"
  CW_FAKE_DIR="$TEST_ROOT/$1/cwfake"
  mkdir -p "$CW_FAKE_DIR" "$(dirname "$CW_AGENT_CTL")"
  : > "$CW_FAKE_DIR/calls.log"
  cat > "$CW_AGENT_CTL" <<EOF
#!/usr/bin/env bash
echo "ctl \$*" >> "$CW_FAKE_DIR/calls.log"
[ -f "$CW_FAKE_DIR/ctl-fails" ] && exit 1
: > "$CW_FAKE_DIR/agent-active"
EOF
  chmod 755 "$CW_AGENT_CTL"

  rpm() { [ -f "$CW_FAKE_DIR/installed" ]; }
  dnf() {
    echo "dnf $*" >> "$CW_FAKE_DIR/calls.log"
    [ ! -f "$CW_FAKE_DIR/dnf-fails" ] || return 1
    : > "$CW_FAKE_DIR/installed"
  }
  # 실제 timeout 처럼 -k <초> 옵션과 제한 시간을 건너뛰고 명령을 실행한다. 인자는 기록해 -k 사용을 확인한다.
  timeout() {
    echo "timeout $*" >> "$CW_FAKE_DIR/timeout.log"
    while [ "${1:-}" = -k ]; do shift 2; done
    shift
    "$@"
  }
  systemctl() {
    if [ "$1" = is-active ]; then
      [ -f "$CW_FAKE_DIR/agent-active" ]
      return
    fi
    echo "systemctl $*" >> "$CW_FAKE_DIR/calls.log"
  }
}

teardown_cloudwatch_agent_fixture() {
  unset -f rpm dnf timeout
  systemctl() { return 0; }
}

cw_call_count() {
  grep -c -- "^$1" "$CW_FAKE_DIR/calls.log" || true
}

test_cloudwatch_agent_install_is_idempotent_and_restarts_only_on_config_change() {
  setup_cloudwatch_agent_fixture "cw-agent-idempotent"

  install_cloudwatch_agent >/dev/null
  if [ "$(cw_call_count 'dnf install -y amazon-cloudwatch-agent')" -ne 1 ] || [ "$(cw_call_count 'ctl ')" -ne 1 ]; then
    echo "not ok - first run must install the package and start the agent once" >&2
    return 1
  fi
  assert_contains "^ctl -a fetch-config -m ec2 -s -c file:$CW_AGENT_CONFIG_PATH$" "$CW_FAKE_DIR/calls.log" \
    "agent must be (re)started from the managed config file"
  assert_contains '^timeout -k 30 300 dnf install -y amazon-cloudwatch-agent$' "$CW_FAKE_DIR/timeout.log" \
    "package install must be bounded with a kill grace period"
  assert_contains "^timeout -k 30 300 $CW_AGENT_CTL -a fetch-config" "$CW_FAKE_DIR/timeout.log" \
    "agent restart must be bounded with a kill grace period"
  python3 -m json.tool "$CW_AGENT_CONFIG_PATH" >/dev/null \
    || { echo "not ok - agent config must be valid JSON" >&2; return 1; }
  assert_contains '"namespace": "Rougether/Dev"' "$CW_AGENT_CONFIG_PATH" "agent must publish to the Rougether/Dev namespace"
  assert_contains '"omit_hostname": true' "$CW_AGENT_CONFIG_PATH" "host metrics must carry no host dimension"
  assert_contains '"mem_used_percent"' "$CW_AGENT_CONFIG_PATH" "agent must collect mem_used_percent"
  assert_contains '"swap_used_percent"' "$CW_AGENT_CONFIG_PATH" "agent must collect swap_used_percent"
  assert_contains '"metrics_collection_interval": 60' "$CW_AGENT_CONFIG_PATH" "agent must collect every 60 seconds"
  assert_not_contains 'append_dimensions' "$CW_AGENT_CONFIG_PATH" "alarm dimensions must not depend on the instance"

  install_cloudwatch_agent >/dev/null
  if [ "$(cw_call_count 'dnf ')" -ne 1 ] || [ "$(cw_call_count 'ctl ')" -ne 1 ]; then
    echo "not ok - unchanged config on a running agent must not reinstall or restart" >&2
    return 1
  fi

  CW_NAMESPACE="Rougether/Staging"
  install_cloudwatch_agent >/dev/null
  if [ "$(cw_call_count 'dnf ')" -ne 1 ] || [ "$(cw_call_count 'ctl ')" -ne 2 ]; then
    echo "not ok - a config change must restart the agent without reinstalling" >&2
    return 1
  fi
  assert_contains '"namespace": "Rougether/Staging"' "$CW_AGENT_CONFIG_PATH" "config change must be written"

  rm -f "$CW_FAKE_DIR/agent-active"
  install_cloudwatch_agent >/dev/null
  if [ "$(cw_call_count 'ctl ')" -ne 3 ]; then
    echo "not ok - a stopped agent must be started even when the config is unchanged" >&2
    return 1
  fi
  if [ -n "$(find "$(dirname "$CW_AGENT_CONFIG_PATH")" -name '.*' -type f)" ]; then
    echo "not ok - agent config install must not leave temporary files" >&2
    return 1
  fi
  teardown_cloudwatch_agent_fixture
  echo "ok - CloudWatch agent install is idempotent and restarts only on config change"
}

test_cloudwatch_agent_failures_are_reported_and_retried() {
  setup_cloudwatch_agent_fixture "cw-agent-failure"

  : > "$CW_FAKE_DIR/dnf-fails"
  if install_cloudwatch_agent >/dev/null 2>&1; then
    echo "not ok - package install failure must be reported" >&2
    return 1
  fi
  if [ "$(cw_call_count 'ctl ')" -ne 0 ] || [ -f "$CW_AGENT_CONFIG_PATH" ]; then
    echo "not ok - agent must not be configured when the package install fails" >&2
    return 1
  fi

  rm -f "$CW_FAKE_DIR/dnf-fails"
  : > "$CW_FAKE_DIR/ctl-fails"
  if install_cloudwatch_agent >/dev/null 2>&1; then
    echo "not ok - agent start failure must be reported" >&2
    return 1
  fi
  if [ -f "$CW_AGENT_CONFIG_PATH" ]; then
    echo "not ok - failed config must be removed so the next deploy retries" >&2
    return 1
  fi

  rm -f "$CW_FAKE_DIR/ctl-fails"
  install_cloudwatch_agent >/dev/null
  if [ "$(cw_call_count 'ctl ')" -ne 2 ] || [ ! -f "$CW_AGENT_CONFIG_PATH" ]; then
    echo "not ok - the next deploy must retry configuring the agent" >&2
    return 1
  fi

  local calls_before
  calls_before="$(wc -l < "$CW_FAKE_DIR/calls.log")"
  CW_NAMESPACE='AWS/EC2'
  if install_cloudwatch_agent >/dev/null 2>&1 || install_memory_metrics >/dev/null 2>&1; then
    echo "not ok - reserved or invalid namespaces must be rejected" >&2
    return 1
  fi
  if [ "$(wc -l < "$CW_FAKE_DIR/calls.log")" -ne "$calls_before" ]; then
    echo "not ok - invalid namespace must be rejected before touching the host" >&2
    return 1
  fi
  teardown_cloudwatch_agent_fixture
  echo "ok - CloudWatch agent failures are reported and retried on the next deploy"
}

test_monitoring_install_failure_does_not_block_deploy() {
  reset_scenario "monitoring-nonblocking"
  local dispatch="$TEST_ROOT/monitoring-nonblocking/dispatch.sh"
  local events="$TEST_ROOT/monitoring-nonblocking/events.log"
  local errors="$TEST_ROOT/monitoring-nonblocking/stderr.log"
  local status=0
  awk 'found {print} /^# BEGIN_DEPLOY_EXECUTION$/ {found=1}' "$DEPLOY_SCRIPT" > "$dispatch"
  : > "$events"

  (
    install_container_watch() { echo watch >> "$events"; return 0; }
    install_cloudwatch_agent() { echo agent >> "$events"; return 1; }
    install_memory_metrics() { echo metrics >> "$events"; return 1; }
    deploy_blue_green() { echo deploy >> "$events"; }
    DEPLOY_MODE=blue-green
    # shellcheck disable=SC1090
    source "$dispatch"
  ) >/dev/null 2> "$errors" || status="$?"

  if [ "$status" -ne 0 ]; then
    echo "not ok - monitoring install failures must not fail the deployment (status $status)" >&2
    return 1
  fi
  if [ "$(tr '\n' ' ' < "$events")" != "watch agent metrics deploy " ]; then
    echo "not ok - deploy must run after the monitoring installers: $(tr '\n' ' ' < "$events")" >&2
    return 1
  fi
  assert_contains 'CloudWatch agent install or configuration failed' "$errors" "agent failure must be logged"
  assert_contains 'memory metrics publisher install failed' "$errors" "metrics failure must be logged"
  echo "ok - monitoring install failures do not block the deployment"
}

test_memory_metrics_install_is_idempotent() {
  reset_scenario "metrics-install"
  local calls="$ENV_DIR/systemctl-calls.log"
  systemctl() { echo "$*" >> "$calls"; return 0; }

  install_memory_metrics >/dev/null
  local script_copy="$ENV_DIR/metrics-script-copy"
  cp "$MEMORY_METRICS_SCRIPT_PATH" "$script_copy"
  : > "$calls"
  install_memory_metrics >/dev/null
  systemctl() { return 0; }

  [ -x "$MEMORY_METRICS_SCRIPT_PATH" ] || { echo "not ok - metrics script must be executable" >&2; return 1; }
  bash -n "$MEMORY_METRICS_SCRIPT_PATH" || { echo "not ok - metrics script must be valid bash" >&2; return 1; }
  assert_file_equal "$script_copy" "$MEMORY_METRICS_SCRIPT_PATH" "reinstall must keep the same metrics script"
  assert_not_contains 'daemon-reload' "$calls" "unchanged metrics units must not trigger daemon-reload"
  assert_not_contains '^restart ' "$calls" "unchanged metrics timer must not be restarted"
  assert_contains '^enable --now rougether-memory-metrics.timer$' "$calls" "every deploy must keep the metrics timer enabled"
  assert_contains '^OnUnitActiveSec=1min$' "$SYSTEMD_DIR/rougether-memory-metrics.timer" "metrics must be published every minute"
  assert_contains '^Environment=ROUGETHER_METRICS_NAMESPACE=Rougether/Dev$' "$SYSTEMD_DIR/rougether-memory-metrics.service" \
    "metrics unit must pass the namespace"
  assert_contains '^Environment=ROUGETHER_METRICS_REGION=ap-northeast-2$' "$SYSTEMD_DIR/rougether-memory-metrics.service" \
    "metrics unit must pass the region"
  assert_contains "^ExecStart=$MEMORY_METRICS_SCRIPT_PATH$" "$SYSTEMD_DIR/rougether-memory-metrics.service" \
    "metrics unit must run the installed script"

  printf '# locally modified\n' >> "$MEMORY_METRICS_SCRIPT_PATH"
  : > "$calls"
  systemctl() { echo "$*" >> "$calls"; return 0; }
  install_memory_metrics >/dev/null
  systemctl() { return 0; }
  assert_file_equal "$script_copy" "$MEMORY_METRICS_SCRIPT_PATH" "drifted metrics script must be restored"
  assert_contains '^daemon-reload$' "$calls" "changed metrics files must reload systemd"
  assert_not_contains '^restart ' "$calls" "a script-only change must not restart the timer"

  printf '# locally modified\n' >> "$SYSTEMD_DIR/rougether-memory-metrics.timer"
  : > "$calls"
  systemctl() { echo "$*" >> "$calls"; return 0; }
  install_memory_metrics >/dev/null
  systemctl() { return 0; }
  assert_before '^daemon-reload$' '^restart rougether-memory-metrics.timer$' "$calls" \
    "a changed timer file must be reloaded and the running timer restarted"

  AWS_REGION="__AWS_REGION__"
  if install_memory_metrics >/dev/null 2>&1; then
    echo "not ok - an unsubstituted region must be rejected" >&2
    return 1
  fi
  echo "ok - memory metrics publisher install is idempotent"
}

set_container_memory() {
  local root="$1" container_id="$2" current="$3" inactive_file="$4"
  local directory="$root/system.slice/docker-$container_id.scope"
  mkdir -p "$directory"
  printf '%s\n' "$current" > "$directory/memory.current"
  if [ -n "$inactive_file" ]; then
    printf 'anon 1\nfile 2\nactive_file 3\ninactive_file %s\nslab 4\n' "$inactive_file" > "$directory/memory.stat"
  fi
}

test_memory_metrics_script_publishes_max_working_set_per_service() {
  reset_scenario "metrics-script"
  systemctl() { return 0; }
  install_memory_metrics >/dev/null

  local fake="$TEST_ROOT/metrics-script/fake"
  local cgroup="$TEST_ROOT/metrics-script/cgroup"
  local blue green admin batch other
  blue="$(printf '1%.0s' $(seq 1 64))"
  green="$(printf '2%.0s' $(seq 1 64))"
  admin="$(printf '3%.0s' $(seq 1 64))"
  batch="$(printf '4%.0s' $(seq 1 64))"
  other="$(printf '5%.0s' $(seq 1 64))"
  mkdir -p "$fake/bin"
  cat > "$fake/bin/docker" <<EOF
#!/usr/bin/env bash
[ "\$1" = ps ] || exit 0
[ ! -f "$fake/docker-fails" ] || { echo "Cannot connect to the Docker daemon" >&2; exit 1; }
cat "$fake/containers"
EOF
  cat > "$fake/bin/aws" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$@" > "$fake/aws-args"
[ ! -f "$fake/aws-fails" ]
EOF
  chmod 755 "$fake/bin/docker" "$fake/bin/aws"

  printf '%s rougether-user-api-blue\n%s rougether-user-api-green\n%s rougether-admin-api\n%s rougether-batch\n%s rougether-container-watch\nnot-an-id rougether-batch\n' \
    "$blue" "$green" "$admin" "$batch" "$other" > "$fake/containers"
  set_container_memory "$cgroup" "$blue" 943718400 104857600
  set_container_memory "$cgroup" "$green" 524288000 10485760
  set_container_memory "$cgroup" "$admin" 734003200 ""
  set_container_memory "$cgroup" "$batch" 1000 5000
  set_container_memory "$cgroup" "$other" 999999999999 0

  run_metrics() {
    PATH="$fake/bin:$PATH" \
      ROUGETHER_METRICS_CGROUP_ROOT="$cgroup" \
      ROUGETHER_METRICS_NAMESPACE="Rougether/Dev" \
      ROUGETHER_METRICS_REGION="ap-northeast-2" \
      bash "$MEMORY_METRICS_SCRIPT_PATH" 2>> "$fake/metrics.log"
  }

  run_metrics || { echo "not ok - metrics script must succeed when publishing works" >&2; return 1; }
  python3 - "$fake/aws-args" <<'PY' || { echo "not ok - published metric data is wrong" >&2; return 1; }
import json, sys
args = open(sys.argv[1]).read().splitlines()
assert args[:2] == ["cloudwatch", "put-metric-data"], args
assert args[args.index("--region") + 1] == "ap-northeast-2", args
assert args[args.index("--namespace") + 1] == "Rougether/Dev", args
data = json.loads(args[args.index("--metric-data") + 1])
values = {}
for datum in data:
    assert datum["MetricName"] == "container_memory_working_set", datum
    assert datum["Unit"] == "Bytes", datum
    assert datum["Dimensions"] == [{"Name": "Service", "Value": datum["Dimensions"][0]["Value"]}], datum
    values[datum["Dimensions"][0]["Value"]] = datum["Value"]
# blue(900-100MiB)와 green(500-10MiB) 중 큰 값, inactive_file 없으면 current 그대로, inactive 가 크면 0.
assert values == {"user-api": 838860800, "admin-api": 734003200, "batch": 0}, values
PY

  : > "$fake/containers"
  rm -f "$fake/aws-args"
  run_metrics || { echo "not ok - no running containers must not be an error" >&2; return 1; }
  [ ! -f "$fake/aws-args" ] || { echo "not ok - nothing must be published without containers" >&2; return 1; }

  printf '%s rougether-batch\n' "$batch" > "$fake/containers"
  : > "$fake/aws-fails"
  if run_metrics; then
    echo "not ok - put-metric-data failure must fail the unit run" >&2
    return 1
  fi
  assert_contains 'put-metric-data failed' "$fake/metrics.log" "publish failure must be logged"

  # docker 조회 실패는 비0 으로 끝내 journal 에 남기고, 아무것도 보내지 않는다.
  rm -f "$fake/aws-fails" "$fake/aws-args"
  : > "$fake/docker-fails"
  if run_metrics; then
    echo "not ok - docker ps failure must fail the unit run" >&2
    return 1
  fi
  [ ! -f "$fake/aws-args" ] || { echo "not ok - nothing must be published when docker ps fails" >&2; return 1; }
  assert_contains 'docker ps failed' "$fake/metrics.log" "docker failure must be logged"

  # cgroup 을 못 읽은 컨테이너가 있으면 나머지 서비스 값은 보내되 비0 으로 끝낸다.
  rm -f "$fake/docker-fails"
  local missing
  missing="$(printf '6%.0s' $(seq 1 64))"
  printf '%s rougether-batch\n%s rougether-admin-api\n' "$batch" "$missing" > "$fake/containers"
  if run_metrics; then
    echo "not ok - an unreadable container cgroup must fail the unit run" >&2
    return 1
  fi
  assert_contains '"Value":"batch"' "$fake/aws-args" "readable services must still be published"
  assert_not_contains 'admin-api' "$fake/aws-args" "unreadable services must not be published"
  assert_contains 'cgroup not found for rougether-admin-api' "$fake/metrics.log" "missing cgroup must be logged"
  unset -f run_metrics
  echo "ok - memory metrics script publishes the max working set per service"
}

# 배포 스크립트가 보내는 지표와 terraform 알람의 네임스페이스·이름·차원이 어긋나면 알람이 영원히 조용하다.
test_metric_contract_matches_terraform_alarms() {
  reset_scenario "metrics-contract"
  local monitoring_dir="$SCRIPT_DIR/../../deploy/terraform/monitoring"
  local script_file="$TEST_ROOT/metrics-contract/metrics.sh"
  local agent_file="$TEST_ROOT/metrics-contract/agent.json"
  write_memory_metrics_script > "$script_file"
  write_cloudwatch_agent_config > "$agent_file"

  assert_contains 'service_memory_metric = "container_memory_working_set"' "$monitoring_dir/main.tf" \
    "terraform must alarm on the published service metric name"
  assert_contains '^METRIC_NAME="container_memory_working_set"$' "$script_file" "script must publish the alarmed metric name"
  assert_contains 'dimensions *= { Service = each.key }' "$monitoring_dir/alarms.tf" "service alarms must use the Service dimension"
  assert_contains '"Dimensions":\[{"Name":"Service"' "$script_file" "script must publish the Service dimension"
  assert_contains 'default *= "Rougether/Dev"' "$monitoring_dir/variables.tf" "terraform namespace default must match"
  assert_contains 'ROUGETHER_CW_NAMESPACE:-Rougether/Dev' "$DEPLOY_SCRIPT" "deploy namespace default must match"
  assert_contains '"user-api" *= 1280' "$monitoring_dir/variables.tf" "user-api alarm limit must match the container limit"
  assert_contains '"admin-api" *= 768' "$monitoring_dir/variables.tf" "admin-api alarm limit must match the container limit"
  assert_contains '"batch" *= 768' "$monitoring_dir/variables.tf" "batch alarm limit must match the container limit"
  assert_contains 'ROUGETHER_USER_MEMORY_LIMIT:-1280m' "$DEPLOY_SCRIPT" "user-api container limit default"
  assert_contains 'ROUGETHER_ADMIN_MEMORY_LIMIT:-768m' "$DEPLOY_SCRIPT" "admin-api container limit default"
  assert_contains 'ROUGETHER_BATCH_MEMORY_LIMIT:-768m' "$DEPLOY_SCRIPT" "batch container limit default"
  assert_contains '"cloudwatch:namespace" = var.metric_namespace' "$monitoring_dir/main.tf" \
    "EC2 PutMetricData must be limited to the metric namespace"
  assert_contains 'Action   = \["cloudwatch:PutMetricData"\]' "$monitoring_dir/main.tf" \
    "EC2 metrics policy must grant only PutMetricData"
  assert_not_contains 'policy_arn *= .*CloudWatchAgentServerPolicy' "$monitoring_dir/main.tf" \
    "the broad managed agent policy must not be attached"
  # 에이전트가 PutMetricData 외 API(logs·ec2:DescribeTags/Volumes)를 부르게 하는 설정이 없어야 최소 정책으로 동작한다.
  assert_not_contains '"logs"' "$agent_file" "agent must not ship logs under the metrics-only policy"
  assert_not_contains 'append_dimensions' "$agent_file" "agent must not need ec2:DescribeTags"
  assert_not_contains 'ec2_tag\|"disk"\|"diskio"' "$agent_file" "agent must not need EC2 tag or volume APIs"
  assert_contains '"namespace": "Rougether/Dev"' "$agent_file" "agent namespace must match the policy condition"
  assert_contains 'dimensions *= { Service = "user-api" }' "$monitoring_dir/alarms.tf" \
    "service collector heartbeat must watch the user-api series"
  echo "ok - published metrics match the terraform alarm and policy contract"
}

test_legacy_units_cap_memory_and_pass_jvm_options
test_jvm_options_toggle_nmt_and_reject_heap_at_limit
test_units_treat_docker_stop_as_success
test_batch_webex_alert_env_failure_keeps_current_file
test_deploy_lock_is_written_atomically
test_container_watch_retries_after_send_failure
test_container_watch_keeps_oom_state_when_docker_ps_fails
test_container_watch_alerts_unit_failed_during_deploy_after_unlock
test_dispatcher_rejects_invalid_override_before_host_changes
test_dispatcher_removes_deploy_lock_and_preserves_failure_code
test_invalid_memory_override_is_rejected_before_writing_units
test_batch_webex_alert_env_is_idempotent_and_keeps_value_on_invalid_source
test_container_watch_install_is_idempotent
test_container_watch_detects_oom_kill_increase_with_cooldown
test_container_watch_alerts_restarts_but_not_during_deploy
test_cloudwatch_agent_install_is_idempotent_and_restarts_only_on_config_change
test_cloudwatch_agent_failures_are_reported_and_retried
test_monitoring_install_failure_does_not_block_deploy
test_memory_metrics_install_is_idempotent
test_memory_metrics_script_publishes_max_working_set_per_service
test_metric_contract_matches_terraform_alarms
test_ssm_failure_keeps_existing_credentials
test_prune_preserves_rollback_tags_and_checks_free_space
test_prune_fails_when_free_space_is_still_too_low
test_invalid_ssm_json_keeps_existing_credentials
test_webex_alert_refresh_replaces_only_with_valid_values
test_social_auth_refresh_updates_existing_runtime_env
test_social_auth_ssm_failure_keeps_existing_values
test_webex_alert_ssm_failure_keeps_existing_token
test_first_deploy_without_credentials_uses_stub
test_new_credentials_are_restored_with_runtime_wiring
test_restore_failure_is_propagated_and_backup_is_kept
test_rollback_restarts_both_services_in_parallel_then_checks_health
test_rollback_health_failure_is_reported_and_propagated
test_capture_rollback_images_preserves_new_deploy_sha
test_batch_env_is_bootstrapped_from_user_runtime_env
test_batch_env_bootstrap_is_idempotent
test_batch_env_wires_firebase_when_credentials_present
test_admin_origin_secret_refresh_is_fail_closed
test_bots_env_refresh_is_idempotent_and_keeps_value_on_invalid_flag
test_market_engine_env_refresh_is_idempotent_and_keeps_value_on_invalid_flag
test_blue_green_slot_mapping_and_state_validation
test_legacy_state_tolerates_stopped_nginx_leftover_and_retires_it
test_blue_green_units_bind_internal_ports_and_cap_memory
test_memory_preflight_failure_does_not_start_candidate
test_candidate_health_precedes_initial_proxy_cutover
test_candidate_health_failure_never_stops_active_service
test_nginx_config_preserves_websocket_upgrade
test_nginx_reload_failure_restores_previous_config
test_blue_green_rollback_restarts_previous_slot_before_switching_back
test_blue_green_orchestration_is_sequential_and_restarts_batch_once
test_interrupted_deploy_retry_preserves_stable_release_snapshot
test_interrupted_deploy_rejects_a_different_target_sha
test_active_release_is_idempotent
test_legacy_emergency_mode_releases_nginx_ports_before_restart
test_first_batch_deploy_failure_stops_new_batch
test_rollback_stops_batch_when_no_user_admin_images
test_rollback_batch_restores_previous_image_deploy_env
test_rollback_recovers_batch_even_if_user_admin_rollback_fails

echo "deployment script tests passed"
