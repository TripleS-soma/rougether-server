#!/usr/bin/env bash
set -Eeuo pipefail

AWS_REGION="${AWS_REGION:-ap-northeast-2}"
PARAMETER_NAME="${APPLE_CREDENTIALS_PARAMETER:-/rougether-dev/apple/credentials-json}"
PROJECT_TAG="${PROJECT_TAG:-rougether}"
ENVIRONMENT_TAG="${ENVIRONMENT_TAG:-dev}"

if [ "$#" -ne 1 ]; then
  echo "usage: $0 /path/to/apple-credentials.json" >&2
  echo '  JSON shape: {"team_id":"...","key_id":"...","private_key":"-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----","refresh_token_enc_key":"..."}' >&2
  exit 2
fi

credentials_file="$1"

if [ ! -f "$credentials_file" ]; then
  echo "credentials file not found: $credentials_file" >&2
  exit 1
fi

python3 - "$credentials_file" <<'PY'
import json
import os
import sys

path = sys.argv[1]
size = os.path.getsize(path)
if size > 4096:
    raise SystemExit(f"Apple credentials exceed the SSM Standard 4KB limit: {size} bytes")

with open(path, encoding="utf-8") as credentials_file:
    credentials = json.load(credentials_file)

required = ("team_id", "key_id", "private_key")
if any(not credentials.get(key) for key in required):
    raise SystemExit("invalid Apple credentials JSON: team_id/key_id/private_key required")
PY

parameter_count="$(
  aws ssm describe-parameters \
    --parameter-filters "Key=Name,Option=Equals,Values=$PARAMETER_NAME" \
    --query 'length(Parameters)' \
    --output text \
    --region "$AWS_REGION"
)"

if [ "$parameter_count" = "0" ]; then
  version="$(
    AWS_PAGER="" aws ssm put-parameter \
      --name "$PARAMETER_NAME" \
      --description "Rougether user-api Apple Sign In client_secret credentials" \
      --type SecureString \
      --tier Standard \
      --value "file://$credentials_file" \
      --tags "Key=Project,Value=$PROJECT_TAG" "Key=Environment,Value=$ENVIRONMENT_TAG" Key=ManagedBy,Value=upload-script \
      --query Version \
      --output text \
      --region "$AWS_REGION"
  )"
elif [ "$parameter_count" = "1" ]; then
  version="$(
    AWS_PAGER="" aws ssm put-parameter \
      --name "$PARAMETER_NAME" \
      --type SecureString \
      --value "file://$credentials_file" \
      --overwrite \
      --query Version \
      --output text \
      --region "$AWS_REGION"
  )"
else
  echo "multiple SSM parameters matched exact name: $PARAMETER_NAME" >&2
  exit 1
fi

echo "Apple credentials updated in $PARAMETER_NAME (version $version)"
