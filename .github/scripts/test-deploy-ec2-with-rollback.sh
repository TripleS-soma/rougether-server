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
  BATCH_DEPLOY_ENV="$ENV_DIR/batch.deploy.env"
  USER_RUNTIME_ENV="$ENV_DIR/user-api.env"
  ADMIN_RUNTIME_ENV="$ENV_DIR/admin-api.env"
  BATCH_RUNTIME_ENV="$ENV_DIR/batch.env"
  FIREBASE_CREDENTIALS_FILE="$ENV_DIR/firebase-adminsdk.json"
  rollback_user_image=""
  rollback_admin_image=""
  rollback_batch_image=""
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
  write_units "user-image" "admin-image" "batch-image"

  assert_not_contains '^FIREBASE_CREDENTIALS_PATH=' "$USER_RUNTIME_ENV" "missing credentials must remove runtime path"
  assert_not_contains 'firebase-adminsdk.json' "$SYSTEMD_DIR/rougether-user-api.service" "missing credentials must omit bind mount"
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

test_ssm_failure_keeps_existing_credentials
test_invalid_ssm_json_keeps_existing_credentials
test_first_deploy_without_credentials_uses_stub
test_new_credentials_are_restored_with_runtime_wiring
test_restore_failure_is_propagated_and_backup_is_kept
test_capture_rollback_images_preserves_new_deploy_sha
test_batch_env_is_bootstrapped_from_user_runtime_env
test_batch_env_bootstrap_is_idempotent
test_batch_env_wires_firebase_when_credentials_present
test_first_batch_deploy_failure_stops_new_batch
test_rollback_stops_batch_when_no_user_admin_images
test_rollback_batch_restores_previous_image_deploy_env
test_rollback_recovers_batch_even_if_user_admin_rollback_fails

echo "deployment script tests passed"
