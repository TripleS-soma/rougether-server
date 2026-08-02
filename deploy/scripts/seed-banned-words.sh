#!/usr/bin/env bash
# 금칙어 시드 (#209) — deploy/seed/banned_words.json 을 admin API 로 적재한다.
# 이미 등록된 단어는 skip 이라 재실행해도 안전(멱등).
#
# 사용법:
#   ADMIN_PASSWORD='<비밀번호>' ./deploy/scripts/seed-banned-words.sh <admin-base-url>
#   예) ADMIN_PASSWORD=... ./deploy/scripts/seed-banned-words.sh http://3.35.167.122:8081
#   base-url 생략 시 http://localhost:8081 (로컬 H2).
#
# 비밀번호 원문은 저장소/채팅에 남기지 않는다 — dev 는 SSM 에서 조회:
#   aws ssm get-parameter --name /rougether-dev/admin/seed-password \
#     --with-decryption --query 'Parameter.Value' --output text --region ap-northeast-2
set -euo pipefail

BASE_URL="${1:-http://localhost:8081}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"

# 원격 http 는 비밀번호가 평문 전송된다 - dev 어드민에 TLS 가 없는 동안의 의식적 opt-in 만 허용
# (tools/admin-asset-mcp 의 ADMIN_ALLOW_HTTP 정책과 동일).
if [[ "$BASE_URL" == http://* && "$BASE_URL" != http://localhost* && "$BASE_URL" != http://127.0.0.1* ]]; then
    if [[ "${ADMIN_ALLOW_HTTP:-}" != "1" ]]; then
        echo "원격 http URL($BASE_URL)은 관리자 비밀번호가 평문 전송됩니다. 의도한 것이면 ADMIN_ALLOW_HTTP=1 을 함께 설정하세요." >&2
        exit 1
    fi
fi
SEED_FILE="$(cd "$(dirname "$0")/../seed" && pwd)/banned_words.json"

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

# 3) 금칙어 적재 (CSRF 제외 경로, 세션 인증만 필요). seed JSON 의 word 필드만 추출해 보낸다.
echo "적재: $SEED_FILE -> $BASE_URL/admin/banned-words/import"
python3 -c "import json,sys; print(json.dumps([e['word'] for e in json.load(open('$SEED_FILE'))]))" \
    | curl -sf -b "$COOKIE_JAR" -X POST "$BASE_URL/admin/banned-words/import" \
        -H "Content-Type: application/json" \
        -d @-
echo
echo "완료. invalid 항목이 있으면 정규화 결과가 빈/과길이 단어이니 seed 파일을 확인."
