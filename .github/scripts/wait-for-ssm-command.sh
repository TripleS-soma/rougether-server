#!/usr/bin/env bash
set -euo pipefail

command_id="${1:?command id is required}"
instance_id="${2:?instance id is required}"
max_attempts="${SSM_MAX_ATTEMPTS:-600}"
poll_interval_seconds="${SSM_POLL_INTERVAL_SECONDS:-10}"

if ! [[ "$max_attempts" =~ ^[1-9][0-9]*$ ]]; then
  echo "SSM_MAX_ATTEMPTS must be a positive integer" >&2
  exit 2
fi
if ! [[ "$poll_interval_seconds" =~ ^[0-9]+$ ]]; then
  echo "SSM_POLL_INTERVAL_SECONDS must be a non-negative integer" >&2
  exit 2
fi

show_invocation() {
  aws ssm get-command-invocation \
    --command-id "$command_id" \
    --instance-id "$instance_id" \
    --query '{Status:Status,Stdout:StandardOutputContent,Stderr:StandardErrorContent}' \
    --output json
}

status="Pending"
for i in $(seq 1 "$max_attempts"); do
  status="$(
    aws ssm get-command-invocation \
      --command-id "$command_id" \
      --instance-id "$instance_id" \
      --query 'Status' \
      --output text 2>/dev/null || echo Pending
  )"

  case "$status" in
    Success|Failed|Cancelled|TimedOut)
      break
      ;;
  esac

  echo "waiting for SSM deploy command ($i/$max_attempts): $status"
  if [ "$i" -lt "$max_attempts" ]; then
    sleep "$poll_interval_seconds"
  fi
done

case "$status" in
  Success)
    show_invocation
    exit 0
    ;;
  Failed|Cancelled|TimedOut)
    show_invocation
    exit 1
    ;;
esac

echo "SSM deploy command exceeded the monitor limit; cancelling remote execution" >&2
aws ssm cancel-command \
  --command-id "$command_id" \
  --instance-ids "$instance_id" >/dev/null || \
  echo "warning: failed to cancel SSM command $command_id" >&2
show_invocation || true
exit 1
