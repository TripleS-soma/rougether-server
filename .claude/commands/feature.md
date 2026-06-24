---
description: 스펙(../rougether-spec) 기반으로 도메인 기능을 구현한다. 범위는 도메인 전체 / 서브리소스 / 엔드포인트 / 자연어 모두 가능.
argument-hint: <도메인> [서브리소스|엔드포인트|기능 설명]
---

스펙을 읽어 `$ARGUMENTS` 범위의 기능을 멀티모듈 구조에 맞게 구현한다. 계획·진행상태는 `docs/work/`에 기록한다.

## 0. 범위 해석

`$ARGUMENTS`에서 대상 도메인과 범위를 파악한다:

- `routine-todo` → 도메인 전체 (api.md의 모든 서브리소스)
- `routine-todo categories` → 서브리소스 1묶음
- `routine-todo POST /routines/{id}/logs` → 엔드포인트 1개
- `루틴 완료 API` (자연어) → 스펙에서 매칭되는 유스케이스

도메인 이름이 불확실하면 `ls ../rougether-spec/domains/`로 확인한다.

## 1. 스펙 조사 (spec-researcher 호출)

`spec-researcher` 서브에이전트를 호출해 해당 범위의 요구사항·테이블·엔드포인트 계약·공통 규칙·**미정값**을 받는다.

## 2. 미정값 게이트 (중요)

spec-researcher가 보고한 미정값(enum 허용값, 코인 보상 금액, 타임존 판정 등)이 이 작업에 필요하면:

- **지어내지 말고 사용자에게 질문**한다.
- 사용자가 답하기 전까지 그 값에 의존하는 부분은 구현하지 않는다.
- `docs/work/<도메인>/<기능>.md` 상단 "미해결" 항목에 남긴다.

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

위험영역(재화·완료·뽑기·인증·집 미션)은 스텝에 트랜잭션·롤백·정합성 테스트를 명시한다.

## 4. 구현

docs/claude/backend.md "산출물을 어느 모듈에 두는가"를 따른다:

- Entity·Repository·migration → `domain` 모듈
- Service·Controller·DTO → `user-api`(또는 admin 기능이면 `admin-api`)
- 에러는 `common.error.ErrorResponse` 형식 / `BusinessException` + `ErrorCode` 사용
- 여러 테이블 쓰기는 `@Transactional`, 조회 서비스는 `@Transactional(readOnly = true)`
- migration 버전 번호는 `domain/src/main/resources/db/migration/`의 최신 다음 번호로

한 스텝을 끝낼 때마다 계획 파일의 체크박스를 `[x]`로 갱신한다.

## 5. 테스트

docs/claude/backend.md "테스트 정책"을 따른다. 모든 기능에 의미 있는 테스트를 작성하고, 위험영역은 트랜잭션·롤백·정합성까지 검증한다.

## 6. 검증

```bash
./gradlew test
```

통과할 때까지 수정한다. 위험영역을 건드렸으면 `reviewer` 서브에이전트로 1차 리뷰를 돌린다.

## 7. 마무리

- 계획 파일·README 롤업의 상태를 최종 갱신한다.
- `bootRun` 등 long-running 세션을 남기지 않는다.
- 커밋·PR은 사용자가 요청할 때만 한다 (필요하면 `/pr`).
