# 오늘 현황(GET /today) API 구현 계획
출처: ../rougether-spec/domains/routine-todo/ · 생성: 2026-06-28

오늘 수행 대상 루틴·투두를 카테고리별로 묶고 진행률·스트릭을 함께 반환하는 **읽기 전용** 집계 API. 재화·완료 변경 없음(위험영역 아님). JWT 인증 + `user_id` 소유권. 타임존 `Asia/Seoul` 고정.

- `GET /api/v1/today` (query `date?` — 기본 오늘 KST)

## 결정값 (확정 — 2026-06-28)
- **오늘 대상 루틴 산출**(status=ACTIVE만, PAUSED/ARCHIVED 제외, `starts_on<=date<=ends_on` 범위 내):
  - `repeat_type=DAILY` → 매일 대상.
  - `repeat_type=WEEKLY` → `repeat_days.daysOfWeek`에 `date`의 요일(MON~SUN)이 포함되면 대상.
  - (`WEEKLY_COUNT`는 기능 제거됨 — 처리 불필요)
- **오늘 대상 투두**: `due_date <= date`(오늘까지 마감, overdue 포함) AND deleted 아님. 완료(COMPLETED)·미완료 모두 노출(완료수 집계 위해).
- **완료 판정**: 루틴 → 당일 `routine_logs`(`routine_date==date`, status COMPLETED) 존재 여부. 투두 → `status==COMPLETED`.
- **그룹/정렬**: 카테고리별 묶음. 미분류(category=null)는 별도 그룹. 루틴은 `scheduled_time` 시간순(null은 뒤).
- **진행 요약**: `completedCount`(완료 루틴+투두), `remainingCount`(미완료), `progressRate`(completed/total, total 0이면 0). 루틴+투두 합산 기준.
- **스트릭**: `streaks`에서 `currentCount`, `longestCount`, `lastSuccessDate` 반환(없으면 0/null).
- **응답 구조**: `date`, `categories[]`(각 `categoryId`(nullable), `name`, `routines[]`, `todos[]`), `summary`(completedCount/remainingCount/progressRate), `streak`(currentCount/longestCount/lastSuccessDate). 루틴 항목에 `completed`(bool), 투두 항목에 `status`.

## 미해결 (구현 전 확정 필요)
- 없음

## 사전 확인
- 모든 테이블(routines, routine_logs, todos, streaks, categories) V1 존재. **신규 migration 불필요.**
- 기존 엔티티·리포지토리 재사용. routines/todos/categories plan과 조회 메서드 일부 공유.

## 스텝

### domain 모듈
- [x] domain: `RoutineRepository` — `findByUserIdAndStatusAndDeletedAtIsNull(userId, ACTIVE)`(오늘 대상 후보 조회, in-app에서 repeat 판정). starts_on/ends_on 필터는 `@Query` 또는 in-app.
- [x] domain: `RoutineLogRepository` — `findByRoutine_UserIdAndRoutineDateAndStatus(userId, date, COMPLETED)`(당일 완료 루틴 id 집합).
- [x] domain: `TodoRepository` — `findByUserIdAndDueDateLessThanEqualAndDeletedAtIsNull(userId, date)`(오늘까지 마감 투두).
- [x] migration: **불필요**

### user-api 모듈
- [x] user-api: DTO — `TodayResponse`(date, categories[], summary, streak), `TodayCategoryGroup`(categoryId, name, routines[], todos[]), `TodayRoutineItem`(id, title, scheduledTime, authType, completed), `TodayTodoItem`(id, title, dueDate, status, completedAt), `TodaySummary`(completedCount, remainingCount, progressRate), `TodayStreak`(currentCount, longestCount, lastSuccessDate).
- [x] user-api: `TodayService`(`@Transactional(readOnly=true)`) — date 결정(query 또는 오늘 KST) → ACTIVE 루틴 조회 후 repeat 판정(`repeat_days` JSON 파싱: Jackson으로 `daysOfWeek` 추출, DAILY는 무조건) → 당일 완료 log id 집합 조회 → 오늘 마감 투두 조회 → 카테고리별 그룹핑·정렬 → summary·streak 계산. **repeat_days JSON 파싱 헬퍼**(WEEKLY daysOfWeek)와 요일 매칭 로직 포함.
- [x] user-api: `TodayController` — `GET /api/v1/today`(query `date?`). `@CurrentUser AuthUser`. Swagger 존대.

### test
- [x] test: `TodayServiceIntegrationTest`(@DataJpaTest + Flyway) — DAILY 루틴 매일 노출 / WEEKLY 루틴 해당 요일만 노출·다른 요일 제외 / PAUSED·ARCHIVED 제외 / starts_on~ends_on 범위 밖 제외 / 당일 routine_log COMPLETED면 completed=true / 오늘 마감·overdue 투두 노출 / 카테고리별 그룹·미분류 그룹 / scheduled_time 정렬 / summary(completed/remaining/progressRate) 정확 / streak 반영(없으면 0).
- [x] test: `TodayControllerTest`(@WebMvcTest) — 응답 구조 계약(categories/summary/streak), `date` query 동작, 빈 상태(루틴·투두 없음) progressRate 0.

## 위험영역 메모
읽기 전용 — 재화·완료 변경 없음. 위험영역 아님(reviewer 필수 아님). 다만 repeat 판정·요일/타임존 경계 로직은 테스트로 단단히 검증.

## 의존성
- routines·todos·routine-logs plan 이후 구현 권장(데이터·조회 메서드 의존). 단독 구현도 가능하나 그 경우 조회 메서드를 이 plan에서 추가.

## 후속 고려사항 (비차단)
- 방 도메인(장진형)의 스트릭 표시와 `streaks` 공유 — 응답 streak 필드 형태는 방 도메인 합의 시 조정 가능.
