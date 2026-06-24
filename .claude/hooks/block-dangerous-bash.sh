#!/usr/bin/env bash
# PreToolUse(Bash) 가드. 두 가지를 차단함:
#  ① secret/.env 커밋  — git add/commit 에 .env(.example 제외), *.key, *.pem, id_rsa 등 포함
#  ② 파괴적 명령        — git push --force, git reset --hard, rm -rf
# 차단 시 exit 2 로 stderr 메시지를 에이전트에게 돌려줌. 허용 시 exit 0.

input="$(cat)"
cmd="$(printf '%s' "$input" | jq -r '.tool_input.command // empty')"

[ -z "$cmd" ] && exit 0

block() {
  echo "BLOCKED by .claude/hooks/block-dangerous-bash.sh: $1" >&2
  exit 2
}

# ② 파괴적 명령
if printf '%s' "$cmd" | grep -Eq 'git[[:space:]]+push([[:space:]].*)?[[:space:]](--force|-f)([[:space:]]|$)'; then
  block "git push --force 금지. 필요하면 사용자가 직접 실행."
fi
if printf '%s' "$cmd" | grep -Eq 'git[[:space:]]+reset[[:space:]]+--hard'; then
  block "git reset --hard 금지. 되돌릴 수 없음. 사용자가 직접 실행."
fi
if printf '%s' "$cmd" | grep -Eq '(^|[[:space:];&|])rm[[:space:]]+-[a-zA-Z]*[rR][a-zA-Z]*[fF]?'; then
  block "rm -rf 금지. 삭제 대상 확인 후 사용자가 직접 실행."
fi

# ① secret 커밋
if printf '%s' "$cmd" | grep -Eq 'git[[:space:]]+(add|commit)'; then
  if printf '%s' "$cmd" | grep -Eq '\.env\.example'; then
    : # .env.example 은 허용
  elif printf '%s' "$cmd" | grep -Eq '(\.env([[:space:]"./]|$)|\.env\.[a-zA-Z]+|\.key([[:space:]"]|$)|\.pem([[:space:]"]|$)|id_rsa)'; then
    block "secret 파일(.env/*.key/*.pem/id_rsa 등) 커밋 금지. .env.example 만 허용."
  fi
fi

exit 0
