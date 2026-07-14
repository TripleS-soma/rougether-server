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

# Load function definitions only. The first exact call starts the real deployment.
awk '/^capture_rollback_images$/{exit} {print}' "$DEPLOY_SCRIPT" > "$FUNCTIONS_FILE"
source "$FUNCTIONS_FILE"

AWS_MOCK_MODE="fail"
AWS_MOCK_PAYLOAD=""

aws() {
  if [ "$AWS_MOCK_MODE" = "fail" ]; then
    return 1
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
  USER_RUNTIME_ENV="$ENV_DIR/user-api.env"
  FIREBASE_CREDENTIALS_FILE="$ENV_DIR/firebase-adminsdk.json"
  rollback_user_image=""
  rollback_admin_image=""
  firebase_credentials_backup=""
  firebase_credentials_replaced=false
  AWS_MOCK_MODE="fail"
  AWS_MOCK_PAYLOAD=""

  mkdir -p "$ENV_DIR" "$SYSTEMD_DIR"
  printf 'SPRING_PROFILES_ACTIVE=mysql\nDB_PASSWORD=fake-db-password\n' > "$USER_RUNTIME_ENV"
  chmod 600 "$USER_RUNTIME_ENV"
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

test_first_deploy_without_credentials_uses_stub() {
  reset_scenario "first-deploy-stub"
  printf 'FIREBASE_CREDENTIALS_PATH=/stale/path.json\n' >> "$USER_RUNTIME_ENV"

  refresh_firebase_credentials
  ensure_user_runtime_env
  write_units "user-image" "admin-image"

  assert_not_contains '^FIREBASE_CREDENTIALS_PATH=' "$USER_RUNTIME_ENV" "missing credentials must remove runtime path"
  assert_not_contains 'firebase-adminsdk.json' "$SYSTEMD_DIR/rougether-user-api.service" "missing credentials must omit bind mount"
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
  write_units "new-user-image" "new-admin-image"
  assert_contains '"project_id":"new"' "$FIREBASE_CREDENTIALS_FILE" "new credentials must be installed before restart"

  restore_firebase_credentials
  ensure_user_runtime_env
  write_units "old-user-image" "old-admin-image"

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
DEPLOYED_SHA=old-deploy-sha
EOF

  capture_rollback_images

  if [ "$rollback_user_image" != "old-user-image" ] || [ "$rollback_admin_image" != "old-admin-image" ]; then
    echo "not ok - rollback images must be read from deploy state" >&2
    return 1
  fi

  if [ "$DEPLOYED_SHA" != "new-deploy-sha" ]; then
    echo "not ok - previous deploy state must not overwrite the new deploy SHA" >&2
    return 1
  fi

  echo "ok - rollback state preserves the new deploy SHA"
}

test_ssm_failure_keeps_existing_credentials
test_invalid_ssm_json_keeps_existing_credentials
test_first_deploy_without_credentials_uses_stub
test_new_credentials_are_restored_with_runtime_wiring
test_restore_failure_is_propagated_and_backup_is_kept
test_rollback_restarts_both_services_in_parallel_then_checks_health
test_rollback_health_failure_is_reported_and_propagated
test_capture_rollback_images_preserves_new_deploy_sha

echo "deployment script tests passed"
