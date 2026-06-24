#!/usr/bin/env bash
# PreToolUse(Edit|Write) 가드. 자율 루프가 어기면 사고인 두 가지를 차단함:
#  ① 이미 있는 Flyway migration 파일 수정 — 체크섬 깨져 앱 기동 불능. 새 V{n} 파일만 허용.
#  ② 테스트 비활성화 — src/test 에 @Disabled/@Ignore/assumeTrue(false) 추가로 테스트 끄기 차단.
# 차단 시 exit 2 로 stderr 메시지를 에이전트에게 돌려줌. 허용 시 exit 0.

input="$(cat)"
file="$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty')"
content="$(printf '%s' "$input" | jq -r '.tool_input.new_string // .tool_input.content // empty')"

[ -z "$file" ] && exit 0

block() {
  echo "BLOCKED by .claude/hooks/block-file-edits.sh: $1" >&2
  exit 2
}

# ① 이미 적용된 migration 수정 (파일이 이미 존재할 때만 — 새 V{n} 생성은 허용)
if printf '%s' "$file" | grep -Eq 'db/migration/.*V[0-9].*\.sql$' && [ -f "$file" ]; then
  block "이미 있는 migration 파일 수정 금지(Flyway 체크섬 깨짐). 스키마 변경은 새 V{n}__*.sql 파일을 추가할 것."
fi

# ② 테스트 비활성화
if printf '%s' "$file" | grep -Eq 'src/test/' \
  && printf '%s' "$content" | grep -Eq '@Disabled|@Ignore|assumeTrue[[:space:]]*\([[:space:]]*false'; then
  block "테스트 비활성화(@Disabled/@Ignore/assumeTrue(false)) 금지. 테스트를 끄지 말고 코드를 고칠 것. 필요하면 사용자가 직접 실행."
fi

exit 0
