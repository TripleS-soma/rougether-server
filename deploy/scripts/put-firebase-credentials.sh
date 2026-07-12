#!/usr/bin/env bash
set -Eeuo pipefail

AWS_REGION="${AWS_REGION:-ap-northeast-2}"
PARAMETER_NAME="${FIREBASE_CREDENTIALS_PARAMETER:-/rougether-dev/firebase/credentials-json}"
PROJECT_TAG="${PROJECT_TAG:-rougether}"
ENVIRONMENT_TAG="${ENVIRONMENT_TAG:-dev}"

if [ "$#" -ne 1 ]; then
  echo "usage: $0 /path/to/firebase-adminsdk.json" >&2
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
    raise SystemExit(f"Firebase credentials exceed the SSM Standard 4KB limit: {size} bytes")

with open(path, encoding="utf-8") as credentials_file:
    credentials = json.load(credentials_file)

required = ("project_id", "private_key", "client_email")
if credentials.get("type") != "service_account" or any(not credentials.get(key) for key in required):
    raise SystemExit("invalid Firebase service account JSON")
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
      --description "Rougether FCM Firebase service account JSON" \
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

echo "Firebase credentials updated in $PARAMETER_NAME (version $version)"
