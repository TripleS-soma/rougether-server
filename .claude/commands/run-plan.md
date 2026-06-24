---
description: /plan이 확정한 plan 파일의 남은 스텝을 자율 반복으로 구현한다.
argument-hint: "<도메인>/<기능> (예: routine-todo/routine-complete)"
---

`docs/work/$ARGUMENTS.md` plan 파일을 입력으로, 남은 `[ ]` 스텝을 **자율 반복으로 burn-down**한다. 이 커맨드는 추측하지 않는다 — 계획에 없는 결정이 필요하면 멈춘다.

## 0. 진입 가드 (반드시 먼저)

plan 파일 `docs/work/$ARGUMENTS.md`를 읽고 확인한다. 아래 중 하나라도 걸리면 **구현하지 말고 멈춰서 사용자에게 알린다**:

- 파일이 없다 → `/plan $ARGUMENTS` 를 먼저 돌리라고 안내
- "미해결" 항목이 0이 아니다 → 무엇이 미확정인지 알리고 `/plan`으로 확정하라고 안내
- 남은 `[ ]` 스텝이 없다 → 이미 완료. 종료.

가드를 통과하면 구현을 시작한다.

## 1. 자율 루프 (한 번에 한 스텝)

남은 `[ ]` 스텝이 없어질 때까지 아래를 반복한다. 한 iteration = 스텝 1개.

1. 맨 위의 안 닫힌 `[ ]` 스텝을 고른다.
2. 스텝을 구현한다. docs/claude/backend.md "산출물을 어느 모듈에 두는가"를 따른다:
   - Entity·Repository·migration → `domain` 모듈
   - Service·Controller·DTO → `user-api`(admin 기능이면 `admin-api`)
   - 에러는 `common.error.ErrorResponse` 형식 / `BusinessException` + `ErrorCode`
   - 여러 테이블 쓰기는 `@Transactional`, 조회 서비스는 `@Transactional(readOnly = true)`
   - migration 버전은 `domain/src/main/resources/db/migration/`의 최신 다음 번호. **이미 적용된 migration 파일은 수정하지 않고 새 번호만 추가**한다.
   - **plan 범위 안에서만** 작업한다. plan에 없는 파일·스텝을 임의로 추가하지 않는다. plan에 빠진 게 보이면 멈추고 보고한다(→ "멈춤 조건").
3. `./gradlew test` 를 돌린다. 깨지면 고친다 — **단 최대 3회까지만**. 3회 안에 못 통과하면 멈추고 원인을 보고한다(→ "멈춤 조건").
   - **기존 테스트를 삭제·약화(assertion 제거 등)해서 통과시키지 않는다.** 테스트가 옳고 코드가 틀린 게 기본 가정이다.
4. 통과하면 plan 파일의 그 스텝을 `[x]`로 갱신한다.
5. 다음 iteration으로.

위험영역(재화·완료·뽑기·인증·집 미션·migration) 또는 `(위험)` 스텝의 `reviewer` 검토는 스텝마다 하지 않고 **"마무리"에서 한 번** 돌린다.

## 2. 멈춤 조건 (추측 금지)

다음 상황에서는 루프를 멈추고 사용자에게 상황을 보고한다 — 임의로 진행하지 않는다:

- 계획에 없던 결정값이 필요해졌다 (enum·보상액·타임존 등) → 미해결로 취급, 멈춤
- 다른 담당자 도메인의 계약을 확정해야 한다 → dependency로 남기고 멈춤
- plan에 없는 파일·스텝이 필요해졌다 → 범위 밖, 멈추고 보고
- 테스트를 3회 고쳐도 계속 깨진다 → 무한 수정 대신 멈추고 원인 보고
- 마무리 `reviewer`가 blocking 이슈를 냈다 → 멈추고 보고

## 3. 이어하기

스텝이 많아 한 번에 다 못 끝내거나 멈춤 조건으로 중단됐으면, 진행상태가 plan 파일의 `[x]`에 남아 있으므로 `/run-plan $ARGUMENTS` 를 다시 호출하면 안 닫힌 스텝부터 이어간다.

## 4. 마무리

- plan 파일·`docs/work/<도메인>/README.md` 롤업의 상태를 최종 갱신한다.
- 멈춤이든 완료든, 위험영역 코드를 작성했으면 누적 변경에 대해 `reviewer`를 **한 번** 돌린다. blocking 이슈가 나오면 멈춤 조건으로 보고한다.
- 위험영역을 건드린 plan은 PR 시 **리뷰 필수**(셀프머지 금지)임을 사용자에게 알린다 (`pr.md` §3).
- `bootRun` 등 long-running 세션을 남기지 않는다.
- **커밋·PR은 하지 않는다.** 필요하면 사용자가 `/pr`로 한다.
