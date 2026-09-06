#!/usr/bin/env bash
# dev RDS SSM 포트 포워딩 — IP 무관, RDS 는 폐쇄(VPC private) 유지.
#
# 사용법:
#   AWS_PROFILE=rougether-isb ./deploy/scripts/db-tunnel.sh        # 이 창을 열어두고
#   → IntelliJ/DBeaver 를 127.0.0.1:13306 으로 접속 (DB/User: rougether)
#   끄려면 이 창에서 Ctrl+C.
#
# 대상은 **로그인한 AWS profile 의 계정**에서 태그·식별자로 찾는다(EC2 tag Name=rougether-dev-app, RDS rougether-dev-mysql).
# Terraform output 을 읽지 않는 이유: 계정 이전(2026-08, #272) 뒤 로컬 checkout 의 state 가 옛 계정 것일 수 있어
# profile 과 state 가 어긋나면 존재하지 않는 인스턴스로 세션을 열려다 실패한다. 같은 profile 로 조회하면 항상 일치한다.
# 임시 대상이 필요하면 INSTANCE_ID / RDS_HOST 로 덮어쓸 수 있다.
set -euo pipefail

REGION="${AWS_REGION:-ap-northeast-2}"
INSTANCE_TAG_NAME="${INSTANCE_TAG_NAME:-rougether-dev-app}"
RDS_IDENTIFIER="${RDS_IDENTIFIER:-rougether-dev-mysql}"

if [ -z "${INSTANCE_ID:-}" ]; then
  INSTANCE_ID="$(aws ec2 describe-instances \
    --filters "Name=tag:Name,Values=${INSTANCE_TAG_NAME}" "Name=instance-state-name,Values=running" \
    --query 'Reservations[0].Instances[0].InstanceId' --output text --region "${REGION}")"
fi
if [ -z "${INSTANCE_ID}" ] || [ "${INSTANCE_ID}" = "None" ]; then
  echo "실행 중인 EC2(tag Name=${INSTANCE_TAG_NAME})를 찾지 못했습니다. AWS_PROFILE 이 dev 계정을 가리키는지 확인하세요." >&2
  exit 1
fi
if [ -z "${RDS_HOST:-}" ]; then
  RDS_HOST="$(aws rds describe-db-instances --db-instance-identifier "${RDS_IDENTIFIER}" \
    --query 'DBInstances[0].Endpoint.Address' --output text --region "${REGION}")"
fi
LOCAL_PORT="${1:-13306}"

echo "SSM 터널: 127.0.0.1:${LOCAL_PORT} -> ${RDS_HOST}:3306  (EC2 ${INSTANCE_ID} 경유)"
if [ -n "${AWS_PROFILE:-}" ]; then
  echo "AWS profile: ${AWS_PROFILE}"
fi
echo "IntelliJ 는 127.0.0.1:${LOCAL_PORT} 로 접속. 끄려면 Ctrl+C."
echo "주의: Homebrew mysql 9.x CLI 는 mysql_native_password 플러그인이 없어 접속이 실패한다 — IntelliJ/DBeaver(JDBC) 또는 mysql-client@8.4 를 쓴다."
echo

exec aws ssm start-session \
  --target "${INSTANCE_ID}" \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters "{\"host\":[\"${RDS_HOST}\"],\"portNumber\":[\"3306\"],\"localPortNumber\":[\"${LOCAL_PORT}\"]}" \
  --region "${REGION}"
