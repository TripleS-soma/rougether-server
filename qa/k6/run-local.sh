#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/qa/k6/compose.yml"
PROFILE="${1:-smoke}"
API_PORT="${K6_API_PORT:-18080}"
DB_PORT="${K6_DB_PORT:-13306}"
BASE_URL="http://127.0.0.1:${API_PORT}"
PROJECT_NAME="rougether-k6-${PPID}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RESULT_DIR="${K6_RESULT_DIR:-$ROOT_DIR/qa/k6/results/${TIMESTAMP}-${PROFILE}}"
APP_LOG="$(mktemp -t rougether-k6-user-api.XXXXXX.log)"
APP_PID=""
APP_PORT_PID=""
COMPOSE_STARTED="false"

stop_app_processes() {
  local listener_pids="$APP_PORT_PID"

  if [ -z "$APP_PID" ]; then
    return
  fi
  if [ -z "$listener_pids" ]; then
    listener_pids="$(lsof -tiTCP:"$API_PORT" -sTCP:LISTEN 2>/dev/null || true)"
  fi

  for pid in $listener_pids; do
    kill "$pid" 2>/dev/null || true
  done
  if kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID" 2>/dev/null || true
    for _ in $(seq 1 20); do
      if ! kill -0 "$APP_PID" 2>/dev/null; then
        break
      fi
      sleep 0.25
    done
    if kill -0 "$APP_PID" 2>/dev/null; then
      kill -KILL "$APP_PID" 2>/dev/null || true
    fi
    wait "$APP_PID" 2>/dev/null || true
  fi

  for _ in $(seq 1 20); do
    if ! lsof -tiTCP:"$API_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
      return
    fi
    sleep 0.25
  done

  listener_pids="$(lsof -tiTCP:"$API_PORT" -sTCP:LISTEN 2>/dev/null || true)"
  for pid in $listener_pids; do
    kill -KILL "$pid" 2>/dev/null || true
  done
}

scrub_sensitive_artifacts() {
  local artifact
  local detected="false"

  for artifact in "$RESULT_DIR/summary.json" "$RESULT_DIR/console.txt" "$APP_LOG"; do
    if [ -f "$artifact" ] && grep -Eq 'accessToken|refreshToken|Authorization:|Bearer ' "$artifact"; then
      : >"$artifact"
      detected="true"
    fi
  done

  if [ "$detected" = "true" ]; then
    echo "sensitive authentication data detected and removed from k6 artifacts" >&2
    return 1
  fi
}

cleanup() {
  local exit_code=$?
  trap - EXIT

  stop_app_processes

  if [ "$COMPOSE_STARTED" = "true" ]; then
    K6_DB_PORT="$DB_PORT" docker compose \
      --project-name "$PROJECT_NAME" \
      --file "$COMPOSE_FILE" \
      down --volumes --remove-orphans >/dev/null 2>&1 || true
  fi

  if ! scrub_sensitive_artifacts; then
    exit_code=1
  fi
  if [ -d "$RESULT_DIR" ]; then
    cp "$APP_LOG" "$RESULT_DIR/user-api.log"
  fi
  if [ "$exit_code" -ne 0 ]; then
    echo "user-api log tail:" >&2
    tail -n 120 "$APP_LOG" >&2 || true
  fi
  rm -f "$APP_LOG"
  exit "$exit_code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for command in docker curl k6 lsof; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "required command not found: $command" >&2
    exit 1
  fi
done

case "$PROFILE" in
  smoke|baseline|stress) ;;
  *)
    echo "profile must be one of: smoke, baseline, stress" >&2
    exit 1
    ;;
esac

if lsof -tiTCP:"$API_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "API port is already in use: $API_PORT" >&2
  exit 1
fi

mkdir -p "$RESULT_DIR"

COMPOSE_STARTED="true"
K6_DB_PORT="$DB_PORT" docker compose \
  --project-name "$PROJECT_NAME" \
  --file "$COMPOSE_FILE" \
  up --detach --wait

(
  cd "$ROOT_DIR"
  SPRING_PROFILES_ACTIVE=mysql \
  SERVER_PORT="$API_PORT" \
  DB_URL="jdbc:mysql://127.0.0.1:${DB_PORT}/rougether_k6?serverTimezone=Asia/Seoul&characterEncoding=UTF-8" \
  DB_USERNAME=rougether \
  DB_PASSWORD=rougether \
  FIREBASE_CREDENTIALS_PATH= \
    ./gradlew :user-api:bootRun
) >"$APP_LOG" 2>&1 &
APP_PID=$!

for attempt in $(seq 1 120); do
  if curl --fail --silent --show-error "$BASE_URL/api/v1/health" >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "user-api exited before becoming healthy" >&2
    exit 1
  fi
  if [ "$attempt" -eq 120 ]; then
    echo "user-api did not become healthy within 120 seconds" >&2
    exit 1
  fi
  sleep 1
done
APP_PORT_PID="$(lsof -tiTCP:"$API_PORT" -sTCP:LISTEN 2>/dev/null | sed -n '1p')"
if [ -z "$APP_PORT_PID" ]; then
  echo "user-api is healthy but no listening process was found on port $API_PORT" >&2
  exit 1
fi

echo "k6 profile=$PROFILE base_url=$BASE_URL results=$RESULT_DIR"
set +e
k6 run \
  --summary-export "$RESULT_DIR/summary.json" \
  --env "BASE_URL=$BASE_URL" \
  --env "PROFILE=$PROFILE" \
  --env "THINK_TIME_SECONDS=${K6_THINK_TIME_SECONDS:-0.2}" \
  "$ROOT_DIR/qa/k6/core-journey.js" \
  2>&1 | tee "$RESULT_DIR/console.txt"
K6_EXIT_CODE=${PIPESTATUS[0]}
set -e

exit "$K6_EXIT_CODE"
