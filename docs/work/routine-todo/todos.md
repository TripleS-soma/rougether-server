# 투두(todos) CRUD + 완료/취소 API 구현 계획
출처: ../rougether-spec/domains/routine-todo/ · 생성: 2026-06-28

내 투두 목록/등록/수정/삭제(soft) + 완료 체크/완료 취소. 투두는 별도 log 테이블 없이 `todos` 자체 `status`/`completed_at`으로 완료를 표현. **완료/취소는 코인 지급·차감을 한 트랜잭션으로 묶는 위험영역(투두는 스트릭 미포함).** JWT 인증 + `user_id` 소유권 guard.

- `GET /api/v1/todos` (filter `categoryId?`, `status?`, `dueDate?`)
- `POST /api/v1/todos`
- `PUT /api/v1/todos/{id}`
- `DELETE /api/v1/todos/{id}` (soft)
- `POST /api/v1/todos/{id}/complete` (완료, 코인 지급)
- `DELETE /api/v1/todos/{id}/complete` (완료 취소, 당일 내, 코인 롤백)

## 결정값 (확정 — 사용자 확인 완료 2026-06-28)
- **코인 보상**: `reward_currency_type=COIN`, **`reward_amount=5` 고정**(루틴 10과 별도, 투두는 5코인).
- **스트릭 미포함**: 투두 완료는 streak 갱신 안 함(features.md — 스트릭은 루틴 기준).
- **완료 경로**: `POST /todos/{id}/complete` / 취소 `DELETE /todos/{id}/complete`(api.md 초안 채택, status PATCH 아님).
- **완료 = todo row 갱신**: 별도 log 없음 → `status=COMPLETED`, `completed_at=now`, reward 필드 기록 + 지갑 COIN +5. **취소 = 되돌리기**: `status=PENDING`, `completed_at=null`, reward_amount=0(또는 기록 유지? → 0으로 리셋), 지갑 COIN -5.
- **완료 취소 가능 조건**: `completed_at`의 날짜(KST)가 **오늘**일 때만 허용. 과거 완료 취소 → `409 TODO_NOT_CANCELABLE`. (타임존 `Asia/Seoul` 고정)
- **멱등성**: 이미 COMPLETED인 투두 재완료 → `409 TODO_ALREADY_COMPLETED`. PENDING 아닌데 취소 외 작업 시 상태 검증.
- **삭제(soft delete)**: `deleted_at` 설정. 투두는 자체 레코드라 완료 기록도 같이 사라짐(별도 정리 불필요). 완료 상태인 투두 삭제 시 코인 회수는 **하지 않음**(이미 획득한 보상은 유지 — 취소가 아닌 삭제이므로). 단, 목록·today에서 제외.
- **TodoStatus**: `PENDING` / `COMPLETED` (현 enum 재사용, 확정).
- **GET 필터**: `categoryId?`, `status?`(PENDING/COMPLETED), `dueDate?`(해당 날짜) 모두 optional. 미지정 시 deleted 아닌 전체.
- **응답 필드**: `id`, `title`, `description`, `categoryId`(nullable), `dueDate`, `status`, `completedAt`, (완료 응답) `rewardCurrencyType`, `rewardAmount`.

## 미해결 (구현 전 확정 필요)
- 없음

## 사전 확인 (스키마는 이미 존재)
- `todos` 테이블·`fk_todo_user`·`fk_todo_category`는 V1에 존재. **신규 migration 불필요.**
- `Todo` 엔티티·`TodoRepository`(+`clearCategory`)는 이미 존재 → 확장만. `TodoStatus`·`CurrencyType` enum 존재.

## 스텝

### domain 모듈
- [x] domain: `Todo` 엔티티 확장 — 정적 팩토리 `create(user, category, title, description, dueDate)`(status=PENDING, reward_amount=0), 수정 `update(...)`(부분 변경, categoryId 변경 포함), `complete(currencyType, amount, completedAt)`(status=COMPLETED), `cancelComplete()`(status=PENDING, completedAt=null, reward 0 리셋), `changeCategory(Category)`, `softDelete(Instant)`.
- [x] domain: `TodoRepository` 확장 — `findByIdAndUserIdAndDeletedAtIsNull(id, userId)`(소유권 guard), 목록 + 필터(`categoryId`/`status`/`dueDate` 조합 — 동적이면 `@Query` 또는 메서드 분기). 기존 `findByUserIdAndStatusAndDeletedAtIsNull`(String 파라미터)은 `TodoStatus` 타입 정리. `clearCategory` 그대로.
- [x] domain: `UserWallet.add(int)`/`subtract(int)` 메서드(routine-logs plan과 공유 — 먼저 구현된 쪽 재사용). `UserWalletRepository.findByUserIdAndCurrencyType` 사용.
- [x] migration: **불필요**

### user-api 모듈
- [x] user-api: `TodoErrorCode` — `TODO_NOT_FOUND`(404), `TODO_ALREADY_COMPLETED`(409), `TODO_NOT_COMPLETED`(409, 미완료 취소 시도), `TODO_NOT_CANCELABLE`(409, 당일 아님), `WALLET_NOT_FOUND`(404). 카테고리 소유권 실패는 `CATEGORY_NOT_FOUND` 재사용.
- [x] user-api: DTO — `TodoResponse`, `TodoListResponse`(items), `TodoCreateRequest`(title @NotBlank @Size(max=160), description? @Size(max=N), categoryId?, dueDate?), `TodoUpdateRequest`(전부 optional), 완료 응답 `TodoCompleteResponse`(status, completedAt, rewardCurrencyType, rewardAmount).
- [x] user-api: `TodoService` — `@Transactional(readOnly=true)` 목록/조회(소유권·필터) / `@Transactional` 등록(categoryId 소유권 검증) / 수정 / 삭제(soft). **완료**(`@Transactional`): 소유권 guard → 이미 COMPLETED면 `TODO_ALREADY_COMPLETED` → status COMPLETED·reward 5 COIN 기록 → 지갑 +5. **완료 취소**(`@Transactional`): 소유권 guard → COMPLETED 아니면 `TODO_NOT_COMPLETED` → completed_at 날짜가 오늘(KST) 아니면 `TODO_NOT_CANCELABLE` → 지갑 -5 → status PENDING·completed_at null·reward 0. (위험: 재화 정합성)
- [x] user-api: `TodoController` — `/api/v1/todos` GET(필터)/POST, `/api/v1/todos/{id}` PUT/DELETE, `/api/v1/todos/{id}/complete` POST/DELETE. `@CurrentUser AuthUser`. Swagger 존대.

### test
- [x] test: `TodoServiceIntegrationTest`(@DataJpaTest + Flyway) — 등록 status PENDING·reward 0 / categoryId 소유권 / 타인 투두 TODO_NOT_FOUND / 수정·삭제(soft, 목록 제외) / 필터(categoryId·status·dueDate).
- [x] test: `TodoCompletionServiceIntegrationTest` — 완료 시 status COMPLETED·completed_at·reward 5 COIN·지갑 +5 / 재완료 TODO_ALREADY_COMPLETED / 취소 시 PENDING·completed_at null·지갑 -5 / 미완료 취소 TODO_NOT_COMPLETED / 당일 아닌 완료 취소 TODO_NOT_CANCELABLE. (위험)
- [x] test: `TodoCompletionRollbackTest`(@SpringBootTest + @MockitoSpyBean) — 완료 트랜잭션 중 지갑 단계 실패 시 todo 상태·코인 전부 롤백(트랜잭션 경계). (위험)
- [x] test: `TodoControllerTest`(@WebMvcTest) — 요청/응답 계약, title 누락 400, POST 201, complete 200/201, 재완료 409, DELETE complete 200/204, 없는 투두 404.

## 위험영역 메모 (재화·완료)
완료/취소는 todos + user_wallets **2개 테이블 변경** → `@Transactional` 필수, 롤백·정합성 테스트 필수. `/run-plan` 종료 후 `reviewer` 검토 + PR 리뷰 필수.

## reviewer blocking (구현 후 발견 — 사용자 확정 완료 2026-06-30)
- **음수 잔액** → **방어적 가드 추가로 해결**. `cancelComplete`에서 회수 전 `wallet.getBalance() < rewardAmount`면 `WALLET_INSUFFICIENT(409)`로 차단(음수 진입만 방지). 회수 UX 정책(취소 차단 vs 마이너스 허용)은 여전히 **재화 도메인(장진형)** 합의 필요 → 합의 시 가드 재검토. 테스트: `받은_코인을_소비한_뒤_취소하면_WALLET_INSUFFICIENT`.
- **동시성 중복 지급/회수**(더블탭 시 코인 5+5) → 사용자 결정 **현행 유지**(plan대로 app-layer status 검사만). `@Version` 낙관락/조건부 UPDATE는 **후속 과제**로 남김. PR 시 이 한계를 노트로 명시할 것.

## 후속 고려사항 (비차단)
- 완료 상태 투두 삭제 시 코인 회수 정책: **현행 유지(미회수)** 확정 — 루틴 삭제도 동일(삭제 시 코인 유지, 일관). 어뷰징 우려 생기면 재검토.
- 지갑 row 미존재 → **해결**: 가입(`AuthService.devLogin` 신규 유저) 시 COIN 지갑(잔액 0) 자동 생성. `UserWallet.create` 팩토리 추가. 루틴/투두 완료 공통 unblock.
