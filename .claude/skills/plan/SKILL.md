---
name: plan
description: 스펙 기반으로 도메인 기능의 구현 계획을 세운다. 미정값을 사람과 확정해 plan 파일을 생성한다. 구현은 /run-plan이 한다.
metadata:
  version: 0.1.0
---

# Plan

스펙을 읽어 전달받은 범위(`<도메인> [서브리소스|엔드포인트|기능 설명]`)의 **구현 계획**을 세우고 `docs/work/`에 기록한다. 이 스킬은 코드를 구현하지 않는다 — 계획만 확정한다. 구현은 plan이 확정된 뒤 `/run-plan`이 자율로 수행한다.

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
- 확정 못 한 항목은 plan 파일 "미해결"에 남기고, 해당 스텝은 보류 표시한다.

## 3. 계획 파일 작성

유스케이스 단위로 `docs/work/<도메인>/<기능>.md`를 만들고(없으면 폴더도 생성), `docs/work/<도메인>/README.md` 롤업 표를 갱신한다. 형식:

```markdown
# <기능> 구현 계획
출처: ../rougether-spec/domains/<도메인>/  · 생성: <YYYY-MM-DD>

## 미해결 (구현 전 확정 필요)
- [ ] <미정 항목> — 사용자 확인 대기
- (없으면 "없음")

## 스텝
- [ ] domain: <Entity/Repository>
- [ ] migration: V{n}__<...>.sql
- [ ] user-api: <Service>
- [ ] user-api: <Controller + DTO>
- [ ] test: <Service/Controller 테스트>
```

스텝은 docs/claude/backend.md "산출물을 어느 모듈에 두는가"를 따라 모듈별로 쪼갠다:

- Entity·Repository·migration → `domain` 모듈
- Service·Controller·DTO → `user-api`(또는 admin 기능이면 `admin-api`)
- migration 버전 번호는 `domain/src/main/resources/db/migration/`의 최신 다음 번호로

위험영역(재화·완료·뽑기·인증·집 미션·migration)은 스텝에 트랜잭션·롤백·정합성 테스트를 명시하고 스텝 끝에 `(위험)` 표시를 붙인다. `/run-plan`은 이 스텝을 멈추지 않고 구현하되, 끝나면 `reviewer` 서브에이전트로 검토하고 PR을 **리뷰 필수**로 표시한다. 결정값은 이미 이 plan에서 다 닫았으므로 실행 중 사람 확인은 필요 없다.

## 4. 마무리 (plan 확정)

- 미해결 항목이 **0이면** plan이 확정된 것이다. 사용자에게 "확정됨 — `/run-plan <도메인>/<기능>` 으로 실행 가능" 이라고 알린다.
- 미해결이 남아 있으면 무엇을 더 확정해야 하는지 사용자에게 알린다. 그 plan은 아직 `/run-plan` 대상이 아니다.
- 이 스킬은 코드를 구현하지 않으므로 `./gradlew` 실행·커밋·PR을 하지 않는다.
