#!/usr/bin/env bash
set -euo pipefail

# 먼저 정상 종료를 요청하고, 응답 지연으로 끝나지 않으면 이 작업의 모니터 PID만 종료함.
monitor_pid="${1:-}"
stop_file="${2:?stop file is required}"
mkdir -p "$(dirname "$stop_file")"
touch "$stop_file"
[ -n "$monitor_pid" ] || exit 0
if ! [[ "$monitor_pid" =~ ^[1-9][0-9]*$ ]] || [ "$monitor_pid" -le 1 ]; then
  echo "::warning::Invalid health monitor PID"
  exit 2
fi

grace="${HEALTH_MONITOR_STOP_GRACE_SECONDS:-15}"
term_grace="${HEALTH_MONITOR_TERM_GRACE_SECONDS:-5}"
if ! [[ "$grace" =~ ^[0-9]+$ && "$term_grace" =~ ^[0-9]+$ ]]; then
  echo "::warning::Invalid health monitor shutdown grace"
  exit 2
fi

is_running() {
  local state
  kill -0 "$monitor_pid" 2>/dev/null || return 1
  state="$(ps -o stat= -p "$monitor_pid" 2>/dev/null || true)"
  # 아직 부모가 회수하지 않은 종료 프로세스는 더 이상 로그를 쓰지 않음.
  ! [[ "$state" =~ ^[[:space:]]*Z ]]
}

wait_stopped() {
  local deadline=$(( SECONDS + $1 ))
  while is_running; do
    [ "$SECONDS" -lt "$deadline" ] || return 1
    sleep 1
  done
}

if wait_stopped "$grace"; then exit 0; fi
kill -TERM "$monitor_pid" 2>/dev/null || true
if wait_stopped "$term_grace"; then exit 0; fi
echo "::warning::Health monitor exceeded shutdown grace; forcing exit. Its summary may be incomplete."
kill -KILL "$monitor_pid" 2>/dev/null || true
if ! wait_stopped 3; then
  echo "::warning::Could not confirm health monitor termination"
  exit 1
fi
