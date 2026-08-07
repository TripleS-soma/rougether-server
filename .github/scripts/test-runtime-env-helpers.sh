#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../../deploy/terraform/ec2/templates/runtime-env-helpers.sh"

assert_equal() {
  local expected="$1"
  local actual="$2"
  local message="$3"

  if [ "$actual" != "$expected" ]; then
    echo "not ok - $message" >&2
    printf 'expected: %q\nactual:   %q\n' "$expected" "$actual" >&2
    return 1
  fi
}

actual_pem=$'-----BEGIN PRIVATE KEY-----\nABC123\n-----END PRIVATE KEY-----\n'
crlf_pem=$'-----BEGIN PRIVATE KEY-----\r\nABC123\r\n-----END PRIVATE KEY-----\r\n'
escaped_pem='-----BEGIN PRIVATE KEY-----\nABC123\n-----END PRIVATE KEY-----\n'

assert_equal "$escaped_pem" "$(printf '%s' "$actual_pem" | escape_multiline_env_value)" \
  "actual LF must be escaped before writing a Docker env file"
assert_equal "$escaped_pem" "$(printf '%s' "$crlf_pem" | escape_multiline_env_value)" \
  "CRLF must normalize to the same escaped value"
assert_equal "$escaped_pem" "$(printf '%s' "$escaped_pem" | escape_multiline_env_value)" \
  "an already escaped SSM value must remain unchanged"
assert_equal "single-line-value" "$(printf '%s' 'single-line-value' | escape_multiline_env_value)" \
  "ordinary one-line values must remain unchanged"

echo "runtime env helper tests passed"
