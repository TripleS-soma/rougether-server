#!/usr/bin/env bash
# 고양이 악세사리 25종 카탈로그 + 캐릭터별 렌더 프로필을 admin API로 멱등 적재한다.
#
# 사용법:
#   ADMIN_PASSWORD='<비밀번호>' ./deploy/scripts/seed-character-accessories.sh <admin-base-url>
#   dev 원격 HTTP는 ADMIN_ALLOW_HTTP=1을 함께 명시해야 한다.
set -euo pipefail

BASE_URL="${1:-http://localhost:8081}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
SEED_FILE="$(cd "$(dirname "$0")/../seed" && pwd)/character_accessories.json"

if [[ "$BASE_URL" == http://* && "$BASE_URL" != http://localhost* && "$BASE_URL" != http://127.0.0.1* ]]; then
    if [[ "${ADMIN_ALLOW_HTTP:-}" != "1" ]]; then
        echo "원격 http URL($BASE_URL)은 관리자 비밀번호가 평문 전송됩니다. 의도한 것이면 ADMIN_ALLOW_HTTP=1을 함께 설정하세요." >&2
        exit 1
    fi
fi
if [[ -z "${ADMIN_PASSWORD:-}" ]]; then
    echo "ADMIN_PASSWORD 환경변수가 필요합니다. dev 값은 SSM에서 조회하세요." >&2
    exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
    echo "jq가 필요합니다." >&2
    exit 1
fi

COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT

CSRF_TOKEN="$(curl -sf -c "$COOKIE_JAR" "$BASE_URL/login" \
    | grep -o 'name="_csrf" value="[^"]*"' | sed 's/.*value="//;s/"//')"
LOGIN_REDIRECT="$(curl -sf -b "$COOKIE_JAR" -c "$COOKIE_JAR" -o /dev/null -w '%{redirect_url}' \
    -X POST "$BASE_URL/login" \
    --data-urlencode "username=$ADMIN_USERNAME" \
    --data-urlencode "password=$ADMIN_PASSWORD" \
    --data-urlencode "_csrf=$CSRF_TOKEN")"
if [[ "$LOGIN_REDIRECT" == *error* ]]; then
    echo "로그인 실패: $LOGIN_REDIRECT" >&2
    exit 1
fi

echo "카탈로그 적재: $BASE_URL/admin/catalog/import"
jq -c '.catalog' "$SEED_FILE" \
    | curl -sf -b "$COOKIE_JAR" -X POST "$BASE_URL/admin/catalog/import" \
        -H "Content-Type: application/json" \
        -d @-
echo

echo "렌더 프로필 적재: $BASE_URL/admin/character-accessory-render-profiles/import"
jq -c '.renderProfiles' "$SEED_FILE" \
    | curl -sf -b "$COOKIE_JAR" -X POST \
        "$BASE_URL/admin/character-accessory-render-profiles/import" \
        -H "Content-Type: application/json" \
        -d @-
echo
