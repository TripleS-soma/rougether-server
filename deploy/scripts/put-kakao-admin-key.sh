#!/usr/bin/env bash
set -Eeuo pipefail

AWS_REGION="${AWS_REGION:-ap-northeast-2}"
PARAMETER_NAME="${KAKAO_ADMIN_KEY_PARAMETER:-/rougether-dev/kakao/admin-key}"
PROJECT_TAG="${PROJECT_TAG:-rougether}"
ENVIRONMENT_TAG="${ENVIRONMENT_TAG:-dev}"

if [ "$#" -ne 1 ]; then
  echo "usage: $0 /path/to/kakao-admin-key.txt" >&2
  exit 2
fi

key_file="$1"

if [ ! -f "$key_file" ]; then
  echo "key file not found: $key_file" >&2
  exit 1
fi

if [ ! -s "$key_file" ]; then
  echo "key file is empty: $key_file" >&2
  exit 1
fi

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
      --description "Rougether user-api Kakao admin key (withdrawal unlink)" \
      --type SecureString \
      --tier Standard \
      --value "file://$key_file" \
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
      --value "file://$key_file" \
      --overwrite \
      --query Version \
      --output text \
      --region "$AWS_REGION"
  )"
else
  echo "multiple SSM parameters matched exact name: $PARAMETER_NAME" >&2
  exit 1
fi

echo "Kakao admin key updated in $PARAMETER_NAME (version $version)"
