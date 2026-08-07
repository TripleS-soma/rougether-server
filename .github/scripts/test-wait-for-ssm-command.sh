#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WAIT_SCRIPT="$SCRIPT_DIR/wait-for-ssm-command.sh"
WORKFLOW="$SCRIPT_DIR/../workflows/docker-publish.yml"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/rougether-ssm-wait-test.XXXXXX")"

cleanup() {
  find "$TEST_ROOT" -type f -delete 2>/dev/null || true
  find "$TEST_ROOT" -depth -type d -exec rmdir {} \; 2>/dev/null || true
}
trap cleanup EXIT

mkdir -p "$TEST_ROOT/bin"
cat > "$TEST_ROOT/bin/aws" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail

if [ "$1 $2" = "ssm cancel-command" ]; then
  printf '%s\n' "$*" >> "$AWS_MOCK_CANCEL_LOG"
  exit 0
fi

if [ "$1 $2" != "ssm get-command-invocation" ]; then
  echo "unexpected aws call: $*" >&2
  exit 2
fi

if [[ "$*" == *"--query Status"* ]]; then
  count=0
  if [ -f "$AWS_MOCK_COUNT_FILE" ]; then
    count="$(cat "$AWS_MOCK_COUNT_FILE")"
  fi
  count=$((count + 1))
  printf '%s' "$count" > "$AWS_MOCK_COUNT_FILE"

  case "$AWS_MOCK_MODE" in
    success)
      if [ "$count" -ge 2 ]; then echo Success; else echo InProgress; fi
      ;;
    failed) echo Failed ;;
    timeout) echo InProgress ;;
    *) exit 2 ;;
  esac
else
  printf '{"Status":"%s"}\n' "$AWS_MOCK_MODE"
fi
MOCK
chmod +x "$TEST_ROOT/bin/aws"

run_scenario() {
  local mode="$1"
  local scenario="$TEST_ROOT/$mode"
  mkdir -p "$scenario"
  AWS_MOCK_MODE="$mode" \
  AWS_MOCK_COUNT_FILE="$scenario/count" \
  AWS_MOCK_CANCEL_LOG="$scenario/cancel.log" \
  SSM_MAX_ATTEMPTS=2 \
  SSM_POLL_INTERVAL_SECONDS=0 \
  PATH="$TEST_ROOT/bin:$PATH" \
    bash "$WAIT_SCRIPT" command-123 instance-456
}

run_scenario success >/dev/null
if [ -e "$TEST_ROOT/success/cancel.log" ]; then
  echo "not ok - successful commands must not be cancelled" >&2
  exit 1
fi
echo "ok - successful SSM commands complete without cancellation"

failed_exit=0
run_scenario failed >/dev/null 2>&1 || failed_exit="$?"
if [ "$failed_exit" -eq 0 ] || [ -e "$TEST_ROOT/failed/cancel.log" ]; then
  echo "not ok - terminal SSM failure must fail without redundant cancellation" >&2
  exit 1
fi
echo "ok - terminal SSM failures propagate"

timeout_exit=0
run_scenario timeout >/dev/null 2>&1 || timeout_exit="$?"
if [ "$timeout_exit" -eq 0 ]; then
  echo "not ok - monitor timeout must fail" >&2
  exit 1
fi
if ! grep -q -- 'ssm cancel-command.*--command-id command-123.*--instance-ids instance-456' \
    "$TEST_ROOT/timeout/cancel.log"; then
  echo "not ok - monitor timeout must cancel the still-running SSM command" >&2
  exit 1
fi
echo "ok - monitor timeout cancels the remote SSM command"

if ! grep -Fq -- 'executionTimeout: ["5400"]' "$WORKFLOW" \
    || ! grep -Fq -- '--timeout-seconds 5400' "$WORKFLOW" \
    || ! grep -Fq -- 'SSM_MAX_ATTEMPTS=600' "$WORKFLOW"; then
  echo "not ok - workflow must give remote execution 90 minutes and monitor it for 100 minutes" >&2
  exit 1
fi
echo "ok - workflow timeout wiring exceeds the worst-case deploy and rollback bound"

echo "SSM command wait tests passed"
