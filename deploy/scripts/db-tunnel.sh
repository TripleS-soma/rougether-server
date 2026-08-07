#!/usr/bin/env bash
# dev RDS SSM 포트 포워딩 — IP 무관, RDS 는 폐쇄(VPC private) 유지.
#
# 사용법:
#   ./deploy/scripts/db-tunnel.sh        # 이 창을 열어두고
#   → IntelliJ/DBeaver 를 127.0.0.1:13306 으로 접속 (DB/User: rougether)
#   끄려면 이 창에서 Ctrl+C.
#
# 대상은 deploy/terraform/ec2 의 현재 Terraform output에서 읽는다.
# 다른 state를 쓰거나 임시 대상이 필요하면 INSTANCE_ID/RDS_HOST로 덮어쓸 수 있다.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TERRAFORM_DIR="${SCRIPT_DIR}/../terraform/ec2"

REGION="${AWS_REGION:-ap-northeast-2}"
if [ -z "${INSTANCE_ID:-}" ]; then
  INSTANCE_ID="$(terraform -chdir="${TERRAFORM_DIR}" output -raw ec2_instance_id)"
fi
if [ -z "${RDS_HOST:-}" ]; then
  RDS_ENDPOINT="$(terraform -chdir="${TERRAFORM_DIR}" output -raw rds_endpoint)"
  RDS_HOST="${RDS_ENDPOINT%:*}"
fi
LOCAL_PORT="${1:-13306}"

echo "SSM 터널: 127.0.0.1:${LOCAL_PORT} -> ${RDS_HOST}:3306  (EC2 ${INSTANCE_ID} 경유)"
if [ -n "${AWS_PROFILE:-}" ]; then
  echo "AWS profile: ${AWS_PROFILE}"
fi
echo "IntelliJ 는 127.0.0.1:${LOCAL_PORT} 로 접속. 끄려면 Ctrl+C."
echo

exec aws ssm start-session \
  --target "${INSTANCE_ID}" \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters "{\"host\":[\"${RDS_HOST}\"],\"portNumber\":[\"3306\"],\"localPortNumber\":[\"${LOCAL_PORT}\"]}" \
  --region "${REGION}"
