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

test_ssm_failure_keeps_existing_credentials
test_invalid_ssm_json_keeps_existing_credentials
test_first_deploy_without_credentials_uses_stub
test_new_credentials_are_restored_with_runtime_wiring
test_restore_failure_is_propagated_and_backup_is_kept

echo "deployment script tests passed"
