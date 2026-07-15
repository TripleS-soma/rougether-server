#!/usr/bin/env bash
# dev RDS SSM 포트 포워딩 — IP 무관, RDS 는 폐쇄(VPC private) 유지.
#
# 사용법:
#   ./deploy/scripts/db-tunnel.sh        # 이 창을 열어두고
#   → IntelliJ/DBeaver 를 127.0.0.1:13306 으로 접속 (DB/User: rougether)
#   끄려면 이 창에서 Ctrl+C.
#
# 값은 deploy/terraform/ec2 의 `terraform output` 기준 — 재배포로 인스턴스가
# 바뀌면 INSTANCE_ID / RDS_HOST 만 갱신하면 된다.
set -euo pipefail

REGION="ap-northeast-2"
INSTANCE_ID="i-0708a9ee0aba5d8ec"
RDS_HOST="rougether-dev-mysql.cvkuqa4iyrq2.ap-northeast-2.rds.amazonaws.com"
LOCAL_PORT="${1:-13306}"

echo "SSM 터널: 127.0.0.1:${LOCAL_PORT} -> ${RDS_HOST}:3306  (EC2 ${INSTANCE_ID} 경유)"
echo "IntelliJ 는 127.0.0.1:${LOCAL_PORT} 로 접속. 끄려면 Ctrl+C."
echo

exec aws ssm start-session \
  --target "${INSTANCE_ID}" \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters "{\"host\":[\"${RDS_HOST}\"],\"portNumber\":[\"3306\"],\"localPortNumber\":[\"${LOCAL_PORT}\"]}" \
  --region "${REGION}"
