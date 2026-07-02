#!/usr/bin/env bash
# items.default_slot 시드 — deploy/seed/slot_assignments.json 을 admin API 로 적재한다.
# asset_key 로 매칭하는 UPDATE 라 재실행해도 안전(멱등). 카탈로그가 먼저 적재돼 있어야 한다.
#
# 사용법:
#   ADMIN_PASSWORD='<비밀번호>' ./deploy/scripts/seed-item-slots.sh <admin-base-url>
#   예) ADMIN_PASSWORD=... ./deploy/scripts/seed-item-slots.sh http://43.203.209.107:8081
#   base-url 생략 시 http://localhost:8081 (로컬 H2).
#
# 비밀번호 원문은 저장소/채팅에 남기지 않는다 — dev 는 SSM 에서 조회:
#   aws ssm get-parameter --name /rougether-dev/admin/seed-password \
#     --with-decryption --query 'Parameter.Value' --output text --region ap-northeast-2
# (자세한 위치: docs/operations/dev-credentials.md)
set -euo pipefail

BASE_URL="${1:-http://localhost:8081}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
SEED_FILE="$(cd "$(dirname "$0")/../seed" && pwd)/slot_assignments.json"

if [[ -z "${ADMIN_PASSWORD:-}" ]]; then
    echo "ADMIN_PASSWORD 환경변수가 필요합니다. (dev 값은 SSM — 상단 주석 참고)" >&2
    exit 1
fi

COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT

# 1) 로그인 페이지에서 CSRF 토큰 + 세션 획득
CSRF_TOKEN="$(curl -sf -c "$COOKIE_JAR" "$BASE_URL/login" \
    | grep -o 'name="_csrf" value="[^"]*"' | sed 's/.*value="//;s/"//')"

# 2) 폼 로그인 (성공 시 / 로 redirect, 실패 시 /login?error)
LOGIN_REDIRECT="$(curl -sf -b "$COOKIE_JAR" -c "$COOKIE_JAR" -o /dev/null -w '%{redirect_url}' \
    -X POST "$BASE_URL/login" \
    --data-urlencode "username=$ADMIN_USERNAME" \
    --data-urlencode "password=$ADMIN_PASSWORD" \
    --data-urlencode "_csrf=$CSRF_TOKEN")"
if [[ "$LOGIN_REDIRECT" == *error* ]]; then
    echo "로그인 실패: $LOGIN_REDIRECT" >&2
    exit 1
fi

# 3) 슬롯 적재 (CSRF 제외 경로, 세션 인증만 필요)
echo "적재: $SEED_FILE -> $BASE_URL/admin/items/slots/import"
curl -sf -b "$COOKIE_JAR" -X POST "$BASE_URL/admin/items/slots/import" \
    -H "Content-Type: application/json" \
    -d @"$SEED_FILE"
echo
echo "완료. notFound 가 있으면 카탈로그 미적재 아이템이니 카탈로그부터 확인."
