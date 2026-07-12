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
COMPOSE_STARTED="false"

cleanup() {
  local exit_code=$?
  trap - EXIT

  if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi

  if [ "$COMPOSE_STARTED" = "true" ]; then
    K6_DB_PORT="$DB_PORT" docker compose \
      --project-name "$PROJECT_NAME" \
      --file "$COMPOSE_FILE" \
      down --volumes --remove-orphans >/dev/null 2>&1 || true
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

for command in docker curl k6; do
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

mkdir -p "$RESULT_DIR"

K6_DB_PORT="$DB_PORT" docker compose \
  --project-name "$PROJECT_NAME" \
  --file "$COMPOSE_FILE" \
  up --detach --wait
COMPOSE_STARTED="true"

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

if grep -Eq 'accessToken|refreshToken|Authorization:|Bearer ' \
  "$RESULT_DIR/summary.json" "$RESULT_DIR/console.txt" "$APP_LOG"; then
  : >"$RESULT_DIR/summary.json"
  : >"$RESULT_DIR/console.txt"
  : >"$APP_LOG"
  echo "sensitive authentication data detected in k6 artifacts" >&2
  exit 1
fi

exit "$K6_EXIT_CODE"
