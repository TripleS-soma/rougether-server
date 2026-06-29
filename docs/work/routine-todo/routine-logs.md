# 루틴 완료 체크 / 완료 취소(routine_logs) API 구현 계획
출처: ../rougether-spec/domains/routine-todo/ · 생성: 2026-06-28

당일 루틴 완료 체크(코인 지급 + 스트릭 갱신)와 완료 취소(코인·스트릭 롤백). **재화·완료·스트릭을 한 트랜잭션으로 묶는 위험영역.** JWT 인증 + 루틴 소유권 guard. 응답 envelope 없음.

- `POST /api/v1/routines/{id}/logs` — 당일 완료 체크
- `DELETE /api/v1/routines/{id}/logs/{logId}` — 완료 취소(당일 내)

## 결정값 (확정 — 사용자 확인 완료 2026-06-28)
- **코인 보상**: `reward_currency_type=COIN`, **`reward_amount=10` 고정**. (open-question 종결: 고정 10코인)
- **완료 취소 방식**: `routine_logs` **row를 hard delete**하고 코인·스트릭을 한 트랜잭션으로 롤백. (api.md DELETE verb 일치, RoutineLogStatus에 CANCELED 불필요)
- **스트릭 포함**: 이번 범위에 포함. per-user 단일 streak row(`uq_streak_user`) 갱신. (아래 스트릭 로직 참고)
- **타임존**: `Asia/Seoul`(KST) 고정. `routineDate` 기본값·"당일" 판정 모두 KST `LocalDate` 기준(ZoneId `Asia/Seoul` 사용, `+9` 하드코딩 금지).
- **멱등성/중복 완료**: 같은 `(routine_id, routine_date)` 재완료 시도 → `409 ALREADY_COMPLETED`. DB `uq_routine_log_routine_date` unique 제약(V3) + 앱 레이어 사전 조회 guard 이중 방어. 취소가 row를 hard delete하므로 취소 후 재완료는 정상 동작.
- **완료 취소 가능 조건**: `logId`의 `routine_date == 오늘(KST)`일 때만 허용. 과거 날짜 로그 삭제 → `409 LOG_NOT_CANCELABLE`(당일 아님). 루틴 소유권도 함께 검증.
- **routineDate 범위**: 요청 `routineDate` 미지정 시 오늘(KST). 미래 날짜 완료 금지(`400`). 과거 날짜 완료 허용 여부는 스트릭 로직 단순화 위해 **오늘만 허용**(미래·과거 모두 거부, MVP). → 완료는 항상 오늘. (취소도 항상 오늘이므로 "당일 내" 조건과 일관)

## 스트릭 로직 (확정 — MVP 단순화)
- streak는 **오늘(KST) 완료에만 반응**. (완료/취소가 오늘로 한정되므로 자연 일관)
- 완료 시: 그 유저의 오늘 COMPLETED log 수를 센 뒤, **오늘 첫 완료일 때만** 스트릭 갱신:
  - `last_success_date == 오늘` → 이미 반영, 변화 없음(방어).
  - `last_success_date == 어제` → `current_count += 1`.
  - 그 외(또는 streak row 없음) → `current_count = 1`(리셋/최초).
  - `longest_count = max(longest_count, current_count)`, `last_success_date = 오늘`, `status = ACTIVE`.
  - streak row 없으면 생성.
- 취소 시: row 삭제 후 그 유저의 오늘 COMPLETED log가 **0개가 되고** `last_success_date == 오늘`이면 롤백:
  - `current_count = max(0, current_count - 1)`, `last_success_date = (current_count>0 ? 어제 : null)`, `status = current_count>0 ? ACTIVE : BROKEN`. `longest_count`는 줄이지 않음(보수적).
  - 오늘 다른 완료가 남아 있으면 streak 변화 없음(그날은 여전히 성공일).
- **한계(문서화)**: 취소 시 `last_success_date`를 "어제"로 복원하는 것은 연속이었다는 가정의 근사값. 비연속 이력 복원은 MVP 범위 밖 → 후속 고려사항.

## 미해결 (구현 전 확정 필요)
- 없음

## 스텝

### domain 모듈
- [x] domain: `RoutineLogRepository` 확장 — `findByRoutineIdAndRoutineDateAndStatus`를 `RoutineLogStatus` 타입으로 정리, `existsByRoutineIdAndRoutineDateAndStatus(routineId, date, COMPLETED)`(중복 완료 guard), `countByRoutine_UserIdAndRoutineDateAndStatus(userId, date, COMPLETED)`(스트릭 판정용 — 유저의 그날 완료 수). `findByIdAndRoutine_UserId(logId, userId)`(취소 시 소유권+조회).
- [x] domain: `StreakRepository`는 `findByUserId` 그대로 사용. `Streak` 엔티티에 갱신 메서드 `applySuccess(LocalDate today)` / `rollback(LocalDate today)` 추가(위 로직 캡슐화), 정적 팩토리 `start(user, today)`.
- [x] domain: `RoutineLog` 엔티티에 정적 팩토리 `complete(routine, routineDate, completedAt, currencyType, amount)`(status=COMPLETED) 추가.
- [x] domain: `UserWallet` 엔티티에 `add(int)` / `subtract(int)` 잔액 변경 메서드 추가(없으면). `UserWalletRepository.findByUserIdAndCurrencyType` 사용(현재 String 파라미터 → `CurrencyType` 타입 정리 또는 `.name()` 전달).
- [x] migration: **V4__add_routine_log_unique.sql** (plan 작성 시점엔 V3 예정이었으나 admin_users가 V3를 차지해 다음 번호 V4로 추가) — `ALTER TABLE routine_logs ADD CONSTRAINT uq_routine_log_routine_date UNIQUE (routine_id, routine_date);` (당일 중복 완료 DB 방어). 기존 `idx_routine_logs_routine_date` 인덱스는 유지(중복이지만 무해, 또는 unique로 대체 가능 — 구현 시 판단). (위험: migration)

### user-api 모듈
- [x] user-api: `RoutineLogErrorCode` — `ALREADY_COMPLETED`(409, 당일 이미 완료), `LOG_NOT_CANCELABLE`(409, 당일 아님), `ROUTINE_LOG_NOT_FOUND`(404), `WALLET_NOT_FOUND`(404, 지갑 미존재 시), `INVALID_ROUTINE_DATE`(400, 미래/과거). 루틴 미존재는 `ROUTINE_NOT_FOUND` 재사용.
- [x] user-api: DTO — `RoutineLogCreateRequest`(routineDate? — 미지정 시 오늘 KST), `RoutineLogResponse`(id, routineDate, status, completedAt, rewardCurrencyType, rewardAmount, streak 요약 `currentCount`/`longestCount`/`lastSuccessDate`). 취소 응답은 `RoutineLogResponse` 또는 갱신 streak 요약(`StreakSummaryResponse`) 반환.
- [x] user-api: `RoutineCompletionService`(또는 RoutineLogService) — **`@Transactional`** 완료: ① 루틴 소유권 guard(`ROUTINE_NOT_FOUND`) ② routineDate 검증(오늘만, `INVALID_ROUTINE_DATE`) ③ 중복 완료 guard(`ALREADY_COMPLETED`) ④ RoutineLog COMPLETED 저장(reward 10 COIN 기록) ⑤ 지갑 COIN +10(없으면 정책상 생성 or `WALLET_NOT_FOUND`) ⑥ 스트릭 갱신(오늘 첫 완료시) — 모두 한 트랜잭션. **취소**: ① 루틴 소유권 + log 조회(`ROUTINE_LOG_NOT_FOUND`) ② log가 해당 routine·당일인지 검증(`LOG_NOT_CANCELABLE`) ③ 지갑 COIN -reward_amount ④ log hard delete ⑤ 스트릭 롤백 — 한 트랜잭션. (위험: 재화·완료·스트릭 정합성)
- [x] user-api: ~~`RoutineLogController`~~ → **사용자 결정으로 `RoutineController`에 병합**(완료/취소는 routines의 하위 리소스라 한 컨트롤러에 둠). 서비스는 `RoutineLogService`로 분리 유지(위험영역 응집), 컨트롤러가 두 서비스를 직접 호출. 파사드 미도입. — `POST /api/v1/routines/{id}/logs`(201), `DELETE /api/v1/routines/{id}/logs/{logId}`(200 또는 204). `@CurrentUser AuthUser`. Swagger 존대.

### test
- [x] test: `RoutineCompletionServiceIntegrationTest`(@DataJpaTest 또는 @SpringBootTest + Flyway) — 완료 시 COMPLETED log 생성·reward 10 COIN·지갑 +10 / 같은 날 재완료 시 ALREADY_COMPLETED / 타인 루틴 완료 시 ROUTINE_NOT_FOUND / 미래·과거 날짜 INVALID_ROUTINE_DATE / **스트릭 갱신(어제 성공 → +1, 비연속 → 1로 리셋, 같은날 2번째 완료는 streak 불변)**. (위험)
- [x] test: `RoutineCancelServiceIntegrationTest` — 취소 시 log hard delete·지갑 -10·스트릭 롤백(오늘 완료 0되면 last_success_date 어제·current_count-1) / 당일 아닌 log 취소 시 LOG_NOT_CANCELABLE / 타인 log 취소 차단 / **오늘 다른 완료가 남으면 streak 불변**. (위험)
- [x] test: `RoutineCompletionRollbackTest`(@SpringBootTest + @MockitoSpyBean) — 완료 트랜잭션 중 스트릭/지갑 단계 실패 시 **log·코인이 전부 롤백**되는지(트랜잭션 경계 실증). 취소도 동일하게 부분 실패 롤백 1건. (위험)
- [x] test: `RoutineLogControllerTest`(@WebMvcTest) — POST 201 + 응답 계약(streak 요약 포함), 중복 완료 409 + ALREADY_COMPLETED, DELETE 204/200, 당일 아님 409.

## 위험영역 메모 (재화·완료·스트릭)
완료/취소는 routine_logs + user_wallets + streaks **3개 테이블을 함께 변경** → `@Transactional` 필수, **롤백·정합성 테스트 필수**. 멱등성은 DB unique + 앱 guard 이중 방어. migration(V3)도 위험영역. `/run-plan` 종료 후 **`reviewer` 서브에이전트 검토 필수 + PR 리뷰 필수** 표시.

## 후속 고려사항 (비차단)
- 과거 날짜 완료(백필) 허용 시 스트릭 재계산 로직 필요 — 현재 오늘로 한정해 회피.
- 취소 시 `last_success_date` "어제" 복원은 근사값 — 정확한 비연속 이력 복원은 별도 설계.
- 지갑(`user_wallets`) row 미존재 시 생성 정책(회원/재화 도메인 소관 — 장진형) 확정 후 ⑤단계 분기 정리.
- 사진 인증(`photo_verifications`)은 별도 plan.
