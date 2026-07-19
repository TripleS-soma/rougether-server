#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROMOTION_SCRIPT="$SCRIPT_DIR/promote-ecr-dev-tags.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/rougether-ecr-promotion-test.XXXXXX")"
CALL_LOG="$TEST_ROOT/calls.log"

cleanup_test_root() {
  find "$TEST_ROOT" -type f -delete 2>/dev/null || true
  find "$TEST_ROOT" -depth -type d -exec rmdir {} \; 2>/dev/null || true
}
trap cleanup_test_root EXIT

argument_value() {
  local target="$1"
  shift

  while [ "$#" -gt 0 ]; do
    if [ "$1" = "$target" ]; then
      printf '%s\n' "$2"
      return 0
    fi
    shift
  done

  return 1
}

aws() {
  printf 'aws %s\n' "$*" >> "$CALL_LOG"

  if [ "$1 $2" = "ecr describe-images" ]; then
    local repository
    repository="$(argument_value --repository-name "$@")"
    local repo="${repository##*/}"

    if { [ "${SCENARIO:-}" = "absent-dev" ] || [ "${SCENARIO:-}" = "delete-failure" ]; } \
        && [ "$repo" = "batch" ]; then
      echo "An error occurred (ImageNotFoundException) when calling DescribeImages" >&2
      return 254
    fi

    if [ "${SCENARIO:-}" = "lookup-failure" ] && [ "$repo" = "batch" ]; then
      echo "An error occurred (AccessDeniedException) when calling DescribeImages" >&2
      return 254
    fi

    if [ "${SCENARIO:-}" = "invalid-digest" ] && [ "$repo" = "batch" ]; then
      echo "None"
      return 0
    fi

    printf 'sha256:old-%s\n' "$repo"
    return 0
  fi

  if [ "$1 $2" = "ecr batch-delete-image" ]; then
    if [ "${SCENARIO:-}" = "delete-failure" ]; then
      echo "1"
    else
      echo "0"
    fi
    return 0
  fi

  echo "unexpected aws call: $*" >&2
  return 1
}

docker() {
  printf 'docker %s\n' "$*" >> "$CALL_LOG"

  if { [ "${SCENARIO:-}" = "promotion-failure" ] \
      || [ "${SCENARIO:-}" = "absent-dev" ] \
      || [ "${SCENARIO:-}" = "delete-failure" ] \
      || [ "${SCENARIO:-}" = "restore-failure" ]; } \
      && [[ "$*" == *"/batch:${GITHUB_SHA}" ]]; then
    return 1
  fi

  if [ "${SCENARIO:-}" = "restore-failure" ] \
      && [[ "$*" == *"/admin-api@sha256:old-admin-api" ]]; then
    return 1
  fi

  return 0
}

run_promotion() (
  source "$PROMOTION_SCRIPT"
  promote_ecr_dev_tags user-api admin-api batch
)

assert_contains() {
  local expected="$1"
  local path="$2"
  local message="$3"

  if ! grep -Fq -- "$expected" "$path"; then
    echo "not ok - $message" >&2
    return 1
  fi
}

assert_not_contains() {
  local unexpected="$1"
  local path="$2"
  local message="$3"

  if grep -Fq -- "$unexpected" "$path"; then
    echo "not ok - $message" >&2
    return 1
  fi
}

reset_scenario() {
  SCENARIO="$1"
  : > "$CALL_LOG"
  export SCENARIO CALL_LOG
  export ECR_REGISTRY="478572912668.dkr.ecr.ap-northeast-2.amazonaws.com"
  export ECR_REPOSITORY_PREFIX="rougether-dev"
  export IMAGE_TAG="dev"
  export GITHUB_SHA="new-sha"
}

test_promotes_all_deployment_images() {
  reset_scenario "success"

  run_promotion

  for repo in user-api admin-api batch; do
    assert_contains "/${repo}:dev 478572912668.dkr.ecr.ap-northeast-2.amazonaws.com/rougether-dev/${repo}:new-sha" \
      "$CALL_LOG" "${repo} must be promoted to :dev"
  done
  echo "ok - all deployment images are promoted"
}

test_source_tag_override_promotes_unverified_to_sha() {
  reset_scenario "success"

  SOURCE_TAG="unverified-new-sha" IMAGE_TAG="new-sha" run_promotion

  for repo in user-api admin-api batch; do
    assert_contains "/${repo}:new-sha 478572912668.dkr.ecr.ap-northeast-2.amazonaws.com/rougether-dev/${repo}:unverified-new-sha" \
      "$CALL_LOG" "${repo} must be promoted from the unverified source tag to :sha"
  done
  echo "ok - SOURCE_TAG override promotes unverified images to :sha"
}

test_partial_failure_restores_all_previous_tags() {
  reset_scenario "promotion-failure"
  local output_log="$TEST_ROOT/promotion-failure.log"
  local exit_code=0

  set +e
  run_promotion > "$output_log" 2>&1
  exit_code="$?"
  set -e

  if [ "$exit_code" -eq 0 ]; then
    echo "not ok - a partial promotion failure must fail the command" >&2
    return 1
  fi

  for repo in user-api admin-api batch; do
    assert_contains "/${repo}:dev 478572912668.dkr.ecr.ap-northeast-2.amazonaws.com/rougether-dev/${repo}@sha256:old-${repo}" \
      "$CALL_LOG" "${repo} must be restored after partial promotion"
  done
  assert_contains "restoring previous :dev tag set" "$output_log" \
    "partial promotion must report set restoration"
  echo "ok - partial promotion restores all previous tags"
}

test_absent_tag_is_removed_during_restore() {
  reset_scenario "absent-dev"
  local exit_code=0

  set +e
  run_promotion > "$TEST_ROOT/absent-dev.log" 2>&1
  exit_code="$?"
  set -e

  if [ "$exit_code" -eq 0 ]; then
    echo "not ok - a failed promotion with an absent previous tag must fail" >&2
    return 1
  fi

  assert_contains "aws ecr batch-delete-image --repository-name rougether-dev/batch --image-ids imageTag=dev" \
    "$CALL_LOG" "an originally absent batch :dev tag must be removed during restore"
  echo "ok - an absent previous tag is removed during restore"
}

test_lookup_failure_stops_before_promotion() {
  reset_scenario "lookup-failure"
  local output_log="$TEST_ROOT/lookup-failure.log"
  local exit_code=0

  set +e
  run_promotion > "$output_log" 2>&1
  exit_code="$?"
  set -e

  if [ "$exit_code" -eq 0 ]; then
    echo "not ok - an ECR lookup failure must fail the command" >&2
    return 1
  fi

  assert_contains "failed to look up current :dev digest for batch" "$output_log" \
    "lookup failure must identify the affected repository"
  assert_not_contains "docker buildx imagetools create" "$CALL_LOG" \
    "promotion must not start when any previous digest lookup fails"
  echo "ok - lookup failure stops before promotion"
}

test_invalid_digest_stops_before_promotion() {
  reset_scenario "invalid-digest"
  local output_log="$TEST_ROOT/invalid-digest.log"
  local exit_code=0

  set +e
  run_promotion > "$output_log" 2>&1
  exit_code="$?"
  set -e

  if [ "$exit_code" -eq 0 ]; then
    echo "not ok - an invalid digest response must fail the command" >&2
    return 1
  fi

  assert_contains "invalid current :dev digest for batch: None" "$output_log" \
    "invalid digest output must identify the affected repository"
  assert_not_contains "docker buildx imagetools create" "$CALL_LOG" \
    "promotion must not start when a digest response is invalid"
  echo "ok - invalid digest stops before promotion"
}

test_restore_failure_is_reported() {
  reset_scenario "restore-failure"
  local output_log="$TEST_ROOT/restore-failure.log"
  local exit_code=0

  set +e
  run_promotion > "$output_log" 2>&1
  exit_code="$?"
  set -e

  if [ "$exit_code" -eq 0 ]; then
    echo "not ok - a promotion with incomplete restore must fail" >&2
    return 1
  fi

  assert_contains "WARNING: :dev restore incomplete" "$output_log" \
    "incomplete compensation must require manual verification"
  echo "ok - incomplete restore is reported"
}

test_semantic_delete_failure_is_reported() {
  reset_scenario "delete-failure"
  local output_log="$TEST_ROOT/delete-failure.log"
  local exit_code=0

  set +e
  run_promotion > "$output_log" 2>&1
  exit_code="$?"
  set -e

  if [ "$exit_code" -eq 0 ]; then
    echo "not ok - a promotion with failed tag deletion must fail" >&2
    return 1
  fi

  assert_contains "WARNING: :dev restore incomplete" "$output_log" \
    "a non-empty ECR failures response must require manual verification"
  echo "ok - semantic ECR delete failure is reported"
}

test_promotes_all_deployment_images
test_source_tag_override_promotes_unverified_to_sha
test_partial_failure_restores_all_previous_tags
test_absent_tag_is_removed_during_restore
test_lookup_failure_stops_before_promotion
test_invalid_digest_stops_before_promotion
test_restore_failure_is_reported
test_semantic_delete_failure_is_reported

echo "ECR promotion script tests passed"
