---
name: commit
description: 현재 변경을 Conventional Commits 규칙에 맞춰 커밋한다. "커밋해줘", "commit" 등을 요청할 때 사용한다.
metadata:
  version: 0.1.0
---

# Commit

현재 작업 내용을 커밋한다. 대상은 전달받은 인자(커밋 메시지 또는 작업 설명) 또는 현재 변경 내용.

## 1. 변경 확인

`git status`와 `git diff`로 무엇이 바뀌었는지 먼저 파악한다. 변경이 여러 성격이 섞여 있으면 어떻게 나눌지 판단한다.

## 2. staging

커밋에 포함할 변경만 `git add`로 staging한다.

- 무관한 임시 파일·로컬 설정(`application.yml` 같은 환경 설정 등)은 사용자가 명시하지 않는 한 포함하지 않는다.
- secret/`.env`/토큰/개인키는 절대 커밋하지 않는다 (PreToolUse 훅이 1차 차단하지만 직접 확인한다).
- 어떤 파일을 넣고 뺄지 애매하면 사용자에게 먼저 확인한다.

## 3. 검증

커밋 전 기본 검증을 돌린다:

```bash
git diff --cached --check   # 화이트스페이스
./gradlew test              # 또는 변경 모듈 compileJava
```

## 4. 커밋 메시지

Conventional Commits 형식으로 작성한다 (scope 미사용, 한국어 가능, 제목에 마침표 없음).

- 제목: `feat: 루틴 완료 API 추가`, `fix: refresh token 만료 처리 수정`
- 본문이 필요하면 산문 대신 불릿(`- `)으로 핵심만 적는다.
- 메시지 끝에 Co-Authored-By 라인을 붙인다:

```
feat: 제목 한 줄

- 변경 핵심 1
- 변경 핵심 2

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
```

## 5. 메시지 확인 후 커밋

작성한 커밋 메시지를 **먼저 사용자에게 보여주고 확인을 받는다.** 사용자가 수정 요청하면 반영해 다시 확인받고, 승인(확인 완료)한 뒤에만 `git commit`을 실행한다. 승인 전에는 커밋하지 않는다.

## 6. 마무리

`git log -1`로 결과를 확인하고, 남은 변경(`git status`)을 사용자에게 보고하고 push한다. PR은 별도 요청이 있을 때만 한다 (PR은 `/pr`).
