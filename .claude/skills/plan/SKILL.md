---
name: plan
description: 스펙 기반으로 도메인 기능의 구현 계획을 세운다. 미정값을 사람과 확정해 GitHub 이슈를 생성한다. 구현은 /run-plan이 한다.
metadata:
  version: 0.2.0
---

# Plan

스펙을 읽어 전달받은 범위(`<도메인> [서브리소스|엔드포인트|기능 설명]`)의 **구현 계획**을 세우고 **GitHub 이슈로 기록**한다. 이 스킬은 코드를 구현하지 않는다 — 계획만 확정한다. 구현은 plan이 확정된(이슈가 생성된) 뒤 `/run-plan`이 자율로 수행한다.

이슈가 정본이다. plan 내용을 `docs/work/`에 파일로 남기지 않는다.

## 0. 범위 해석

전달받은 인자에서 대상 도메인과 범위를 파악한다:

- `routine-todo` → 도메인 전체 (api.md의 모든 서브리소스)
- `routine-todo categories` → 서브리소스 1묶음
- `routine-todo POST /routines/{id}/logs` → 엔드포인트 1개
- `루틴 완료 API` (자연어) → 스펙에서 매칭되는 유스케이스

도메인 이름이 불확실하면 `ls ../rougether-spec/domains/`로 확인한다.

## 1. 스펙 조사 (spec-researcher 호출)

`spec-researcher` 서브에이전트를 호출해 해당 범위의 요구사항·테이블·엔드포인트 계약·공통 규칙·**미정값**을 받는다.

## 2. 미정값 게이트

spec-researcher가 보고한 미정값(enum 허용값, 코인 보상 금액, 타임존 판정 등)을 **이 단계에서 전부 확정한다.** plan을 확정하는 목적이 곧 미정값 제거다 — `/run-plan`은 미정값을 추측하지 않으므로, 여기서 안 닫으면 그 스텝은 영영 실행되지 않는다.

- **지어내지 말고 사용자에게 질문**한다.
- 사용자가 답하기 전까지 그 값에 의존하는 스텝은 plan에 넣지 않는다.
- **미정값이 하나라도 남으면 이슈를 만들지 않는다.** 무엇을 더 확정해야 하는지 사용자에게 알리고 멈춘다. 이슈는 미해결 0일 때만 생성한다.

## 3. 이슈 생성

유스케이스 1개 = 이슈 1개. 미해결이 0이 됐을 때 `gh issue create`로 만든다.

**제목**: 기능 중심으로 쓴다. API path(`GET /api/v1/...`)를 제목에 쓰지 않는다. (예: `내 방 슬롯 조회`, `보유 아이템 인벤토리 조회`)

**라벨**: 도메인을 한국어 라벨로 단다 — `방`·`집`·`멤버`·`루틴/투두`·`상점`·`뽑기` 등. 라벨이 없으면 `gh label create`로 만들고 단다. **담당(assignee)은 지정하지 않는다.**

**본문**: 구현 중심 + 결정값은 짧은 불릿. 산문으로 길게 풀지 않는다. 형식:

```markdown
**<METHOD> /api/v1/...** — <한 줄 기능 설명>. <읽기전용/위험영역 여부>.
출처: spec `domains/<도메인>` · migration <필요 V{n} / 불필요(사유)>

## 결정값
- <확정한 값들을 짧은 불릿으로> (필터·정렬·응답 필드·소유권·에러 등)

## 구현
### domain
- [ ] <Entity/Repository/enum/migration>
### user-api
- [ ] <Service / Controller+DTO / ErrorCode>
### test
- [ ] <Service 통합테스트 / Controller 테스트>

## 후속(비차단)
- <스펙 sync, 다른 엔드포인트로 미루는 의존 등 / 없으면 "없음">
```

체크리스트 스텝은 docs/claude/backend.md "산출물을 어느 모듈에 두는가"를 따라 모듈별로 쪼갠다:

- Entity·Repository·enum·migration → `domain` 모듈
- Service·Controller·DTO·ErrorCode → `user-api`(또는 admin 기능이면 `admin-api`)
- migration 버전 번호는 `domain/src/main/resources/db/migration/`의 최신 다음 번호로

위험영역(재화·완료·뽑기·인증·집 미션·migration)은 해당 스텝에 트랜잭션·롤백·정합성 테스트를 명시한다. `/run-plan`은 이 스텝을 멈추지 않고 구현하되, 끝나면 `reviewer` 서브에이전트로 검토한다. 결정값은 이미 이 plan에서 다 닫았으므로 실행 중 사람 확인은 필요 없다.

본문이 길면 셸 이스케이프를 피하려고 스크래치패드에 `.md`로 쓴 뒤 `gh issue create --body-file`로 만든다.

## 4. 마무리 (plan 확정)

- 이슈를 만들었으면 사용자에게 이슈 번호·URL과 함께 "확정됨 — `/run-plan <이슈번호>` 로 실행 가능" 이라고 알린다.
- 미해결이 남아 이슈를 못 만들었으면 무엇을 더 확정해야 하는지 알린다. 아직 `/run-plan` 대상이 아니다.
- 이 스킬은 코드를 구현하지 않으므로 `./gradlew` 실행·커밋·PR을 하지 않는다.
