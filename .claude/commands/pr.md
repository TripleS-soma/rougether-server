---
description: CONTRIBUTING.md 규칙에 맞춰 브랜치·커밋을 정리하고 PR을 생성한다.
argument-hint: [PR 제목 또는 작업 설명]
---

CONTRIBUTING.md의 브랜치·커밋·PR·리뷰 규칙에 맞춰 현재 작업을 PR로 만든다. 대상 작업은 `$ARGUMENTS` 또는 현재 변경 내용.

## 1. 브랜치 확인

`git status`/`git branch`로 현재 브랜치를 확인한다. `main`이면 새 브랜치를 만든다. 브랜치 이름 규칙(CONTRIBUTING):

```
feat/{issue-number}-{short-name}    fix/...    refactor/...    docs/...    chore/{short-name}
```

이슈 번호가 없으면 짧은 설명만 써도 된다. 예: `feat/routine-complete`.

## 2. 커밋

Conventional Commits 형식으로 커밋한다 (scope 미사용, 한국어 가능, 마침표 없음):

```
feat: 루틴 완료 API 추가
fix: refresh token 만료 처리 수정
```

- 의미 없는 중간 커밋(WIP/temp)이 많으면 PR 전에 정리한다.
- secret/`.env`/토큰/개인키를 절대 커밋하지 않는다 (PreToolUse 훅이 1차 차단하지만 직접 확인한다).
- 커밋 메시지 끝에 Co-Authored-By 라인을 붙인다.

## 3. 리뷰 필수 여부 판단 (CONTRIBUTING §5)

변경 내용이 아래에 해당하면 **리뷰 필수**임을 PR 본문에 명시하고 셀프 머지하지 않는다:

- 인증/인가·토큰·세션
- DB migration·테이블 관계·인덱스
- 재화·보상·뽑기 등 데이터 정합성
- 루틴 완료·스트릭·방 성장 등 멀티 도메인
- API 요청/응답 계약 변경
- 운영 환경 설정·배포·보안

문서·오타·테스트 보강·단순 설정·동작 무변경 리팩터링은 셀프 머지 가능.

## 4. PR 생성

`gh pr create`로 생성한다 (제목은 커밋과 같은 Conventional 형식). 본문 체크리스트(CONTRIBUTING §4):

```markdown
## 작업 목적
<관련 이슈 또는 목적>

## 변경 내용
<요약>

## 테스트
<./gradlew test 결과>

## 체크
- [ ] DB migration 필요 시 이유·영향 범위 기재
- [ ] API 변경 시 요청/응답 예시 또는 문서 링크
- [ ] secret·토큰·개인키·.env 미커밋
- [ ] 리뷰 필수 여부: <필수 | 셀프 머지 가능>
```

기본 머지 방식은 일반 merge다(squash 아님). PR 본문 끝에 Generated with Claude Code 라인을 붙인다.
