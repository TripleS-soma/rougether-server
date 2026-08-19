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
  cat > "$NGINX_CONFIG_FILE" <<EOF
upstream user { server 127.0.0.1:$user_port; }
upstream admin { server 10.0.0.10:$admin_port; }
server { listen 8080; }
server { listen 8081; }
EOF
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
  echo "ok - blue/green state validates color and port pairs"
}

test_blue_green_units_bind_internal_ports_and_cap_memory() {
  reset_scenario "blue-green-units"
  write_blue_green_units

  assert_contains '127.0.0.1:${ROUGETHER_HOST_PORT}:8080' \
    "$SYSTEMD_DIR/rougether-user-api@.service" \
    "user slots must bind only their loopback candidate port"
  assert_contains '--memory 768m --memory-swap 768m' \
    "$SYSTEMD_DIR/rougether-user-api@.service" \
    "user candidate must have a hard Docker memory cap without swap allowance"
  assert_contains 'JAVA_TOOL_OPTIONS=-Xmx512m' \
    "$SYSTEMD_DIR/rougether-user-api@.service" \
    "user JVM heap must stay below its container cap"
  assert_contains '--network host --memory 640m --memory-swap 640m' \
    "$SYSTEMD_DIR/rougether-admin-api@.service" \
    "admin slots must preserve host-network origin verification and cap memory"
  assert_contains '--env-file /etc/rougether/admin-api.env --env SERVER_PORT=${ROUGETHER_HOST_PORT}' \
    "$SYSTEMD_DIR/rougether-admin-api@.service" \
    "admin slot port must override the legacy runtime env value"
  assert_contains '--memory 640m --memory-swap 640m' \
    "$SYSTEMD_DIR/rougether-batch.service" \
    "single batch container must also have a hard memory cap"
  echo "ok - blue/green units isolate candidate ports and cap memory"
}

test_memory_preflight_failure_does_not_start_candidate() {
  reset_scenario "blue-green-memory"
  local meminfo="$ENV_DIR/meminfo"
  local calls="$ENV_DIR/calls.log"
  cat > "$meminfo" <<'EOF'
MemAvailable:     500000 kB
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
    finish_successful_deploy() { trap - ERR; }
    deploy_legacy
  )

  assert_before 'systemctl stop nginx' 'systemctl restart rougether-user-api rougether-admin-api' \
    "$calls" "legacy mode must release Nginx fixed ports before starting legacy containers"
  assert_before 'stop-slot user-api blue' 'systemctl restart rougether-user-api rougether-admin-api' \
    "$calls" "legacy mode must stop active user slot before fixed-port restart"
  assert_before 'stop-slot admin-api green' 'systemctl restart rougether-user-api rougether-admin-api' \
    "$calls" "legacy mode must stop active admin slot before fixed-port restart"
  echo "ok - legacy emergency mode safely releases blue/green routing first"
}

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
test_blue_green_slot_mapping_and_state_validation
test_blue_green_units_bind_internal_ports_and_cap_memory
test_memory_preflight_failure_does_not_start_candidate
test_candidate_health_precedes_initial_proxy_cutover
test_candidate_health_failure_never_stops_active_service
test_nginx_reload_failure_restores_previous_config
test_blue_green_rollback_restarts_previous_slot_before_switching_back
test_blue_green_orchestration_is_sequential_and_restarts_batch_once
test_active_release_is_idempotent
test_legacy_emergency_mode_releases_nginx_ports_before_restart
test_first_batch_deploy_failure_stops_new_batch
test_rollback_stops_batch_when_no_user_admin_images
test_rollback_batch_restores_previous_image_deploy_env
test_rollback_recovers_batch_even_if_user_admin_rollback_fails

echo "deployment script tests passed"
