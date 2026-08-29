# 루틴 / 투두 도메인 (서버 구현 노트)

도메인 계약(기능·API·데이터)의 **정본은 spec repo**에 있습니다. 이 문서와 어긋나면 spec이 우선입니다.

- 정본: [rougether-spec](https://github.com/TripleS-soma/rougether-spec) repo의 `domains/routine-todo/` (prd.md / features.md / api.md)
- 전체 ERD: 같은 repo `erd.md`
- API 공통 규약: 같은 repo `api.md`

이 문서는 이 서버 repo에서의 **구현 노트**(Spring 패키지 구조, 트랜잭션 경계, 서버 특이사항)만 둡니다.

## 구현 노트

- 투두(`todos`)는 `due_date`(마감일)와 별개로 `due_time`(마감 시각, `LocalTime`, nullable) 컬럼을 가진다. 루틴의 `scheduled_time`과 동일하게 초/나노를 0으로 정규화해 저장한다. 등록·수정(`TodoCreateRequest`/`TodoUpdateRequest`)에서 입력받고, 단건·목록·오늘 현황·캘린더 응답(`TodoResponse`/`TodayTodoItem`)에 노출한다. 완료 가능 여부·보상 판정은 기존대로 `due_date`(날짜 단위)만 기준으로 하며 `due_time`은 판정에 관여하지 않는다.
- 오늘 현황·캘린더에서 같은 날짜로 묶인 투두는 루틴의 `scheduled_time` 정렬과 동일하게 `due_time` 오름차순(시각 없는 항목은 뒤로)으로 정렬한 뒤 id 순으로 둔다(`DailyAgendaAssembler`).

### 저녁 미완료 통합 알림 (V58)

- `batch.eveningdigest`는 매시와 애플리케이션 기동 시 실행되며, KST 당일 21:00~23:59에만 현재 날짜를 처리한다. 오늘 수행 대상인 ACTIVE 루틴 중 같은 계보의 완료 로그가 없는 항목과 오늘 마감인 PENDING 투두를 사용자별로 집계하고, 탈퇴 사용자·봇·0건 사용자는 제외한다.
- 적재 단계와 FCM 발송 단계는 서로 다른 Spring Batch step이다. 적재 단계는 `daily_incomplete_digests`의 `(user_id, digest_date)` unique로 사용자·날짜 멱등성을 보장하고, 당시 루틴 계보 id와 투두 id를 `daily_incomplete_digest_targets`에 정규화해 저장한다. `notification.ref_id`는 digest id를 가리킨다. 발송 reader와 최종 writer는 모두 현재 KST 날짜를 다시 확인해, 23:59에 시작한 job이 자정을 넘겨 전날 알림을 보내지 않게 한다.
- 발송 단계는 기존 `ReminderPushWriter`와 `NotificationPushPolicy`를 재사용한다. `DAILY_INCOMPLETE_DIGEST`는 `REMINDER` 설정 그룹이며, 알림의 `push_status`와 digest의 `push_status`·`sent_at`을 같은 처리 흐름에서 동기화한다. 날짜별 PENDING reader를 사용하므로 자정 뒤 전날 알림을 지연 발송하지 않는다.
- FCM 호출 자체의 사용자별 예외는 해당 알림만 `FAILED`로 종결하지만, 설정·토큰·상태 repository 같은 내부 인프라 예외는 push step에서 skip하지 않는다. step/job을 `FAILED`로 남겨 같은 `targetDate` JobInstance를 재시작하고 커밋되지 않은 `PENDING`을 다시 처리한다. 다만 재시작 전에 자정을 넘긴 전날 `PENDING`은 상태를 바꾸거나 소급 발송하지 않고 관리자 `PENDING` 지표로 WATCH한다. FCM 성공 직후 상태 DB 갱신이 실패하면 재시작 과정에서 중복 push 가능성이 있으므로 운영 알림과 함께 관측한다.
- `GET /admin/notification-digests/metrics?days=N`은 digest 생성 수와 `PENDING`/`SENT`/`BLOCKED`/`FAILED` 상태를 날짜별로 집계한다. 완료 전환 분모는 `SENT`만 사용하며, 실제 발송 시각(`sent_at`)부터 120분 미만에 당시 target 중 하나 이상 완료한 digest만 전환으로 센다. 아직 120분 창이 닫히지 않은 건은 별도로 분리한다.
- 운영 WATCH: 현재 관리자 지표는 최대 90일 digest와 연결 알림을 메모리에 읽어 일별로 묶는다. 사용자 규모가 커지기 전에 상태 수는 DB `group by` 집계로 전환하고, 전환 target 조회의 실행 계획과 인덱스를 함께 점검한다.

### 월 캘린더 개수 조회

- `GET /api/v1/calendar/month?yearMonth=YYYY-MM`(`CalendarController.month` → `CalendarService.month`)은 그 달 1일~말일 모든 날짜에 대해 `{date, routineCount, todoCount}`만 내려준다(목록·완료 여부 없음, 대상 없는 날도 0 포함). 달력 화면의 날짜별 표시(개수·점) 용도이며 날짜를 눌렀을 때의 상세는 기존 `GET /api/v1/calendar?date=`를 쓴다.
- 날짜별 소싱 규칙은 일별 캘린더(`day()`)와 동일하게 오늘(KST) 기준 세 구간으로 갈린다 — 그제 이전은 그날 `routine_logs`(COMPLETED+FAILED) 건수, 어제는 그날 유효했던 버전으로 재계산(`recalculateRoutines`, 일별과 공유), 오늘·미래는 현재 ACTIVE 루틴의 반복 대상 판정. 투두는 마감일이 그날인 살아있는 것만 센다. 따라서 월별 개수는 그 날짜의 일별 응답 건수와 항상 일치한다(`CalendarMonthIntegrationTest`가 검증).
- 날짜마다 조회하지 않고 구간별로 묶어 집계한다: 투두는 `TodoRepository.countOwnedByDueDateBetween`(GROUP BY due_date) 1회, 과거 로그는 `RoutineLogRepository.countByUserIdAndRoutineDateBetween`(GROUP BY routine_date) 1회, 어제는 재계산 2회, 오늘·미래는 ACTIVE 목록 1회를 읽고 날짜마다 `RoutineRecurrence.isTargetOn`으로 인메모리 판정한다(한 달 최대 5쿼리). 날짜별 건수 projection은 `domain.support.DailyCount`(`targetDate`/`itemCount`)를 쓴다.
- 이 엔드포인트는 프론트 요청 기반 추가(2026-08-16)다. spec 정본(rougether-spec `domains/routine-todo/api.md` 캘린더 절)에 반영이 필요하다.

### 집 단체미션 연동 표시 (V35)

- 클라이언트가 단체미션↔루틴 연동을 이름 매칭으로 추적하던 것을 서버 저장 식별자로 대체한다(이름이 바뀌면 연동이 끊기는 문제, 프론트 요청 2026-07-29).
- 루틴은 `routines.house_mission_id`(nullable)에 연동된 단체미션 id, 카테고리는 `categories.house_id`(nullable)에 연동된 집 id를 보관하고 등록·수정 요청(`houseMissionId`/`houseId`)으로 받는다. 단건·목록·오늘 현황·캘린더 응답(`RoutineResponse`/`TodayRoutineItem`/`CategoryResponse`)에 노출한다.
- 연동 지정은 해당 집의 ACTIVE 구성원만 가능하다(`HouseLinkValidator`, 집 도메인 소유 컴포넌트). 위반 시 `HOUSE_MISSION_NOT_FOUND`/`HOUSE_NOT_FOUND`/`HOUSE_NOT_MEMBER`.
- 수정 요청에서 이 필드는 null=기존 연동 유지다. `categoryId`(null=해제)와 규칙이 다른 이유: 연동 필드를 모르는 구버전 클라이언트의 수정 요청이 연동을 지우면 안 되기 때문. 해제는 전용 API 로 한다 — `DELETE /api/v1/routines/{id}/house-mission-link`, `DELETE /api/v1/categories/{id}/house-link` (멱등, 대상 데이터 자체는 유지, 과거 자동 기여는 미회수).
- 연동은 집 이벤트 시 서버가 자동 해제한다: 미션 삭제 → 전 구성원의 연동 루틴 해제(`clearHouseMissionLink`), 집 탈퇴·강퇴 → 그 회원의 해당 집 연동 루틴·카테고리 해제(`clearHouseMissionLinksOfMember`/`clearHouseLinkOfMember`, 마지막 구성원 탈퇴로 집이 정리되는 경우 포함). 루틴·카테고리 자체는 개인 데이터라 서버가 삭제하지 않는다(삭제 여부는 클라이언트 UX 결정). bulk 해제는 영속성 컨텍스트를 우회하고 비우지도 않으므로(잠근 house 등이 detach 되면 변경 유실), 같은 트랜잭션에서 이후 루틴·카테고리를 읽지 않는 위치(트랜잭션 끝)에서만 호출한다.
- FK 없이 식별자만 보관한다. 자동 해제가 커버하지 못하는 순간(동시성 등)에 낡은 id 가 보이면 클라이언트는 보유 목록에 없는 id 를 미연동으로 취급한다. 루틴 버전 분기(`copyAsNewVersion`) 시 연동은 새 버전으로 승계된다.
- 연동 루틴을 오늘(KST) 날짜로 완료하면 해당 미션에 수행 체크가 자동 반영된다(`RoutineLogService` → `HouseMissionService.autoContribute`, 완료와 한 트랜잭션). 기여 불가 사유(오늘 이미 기여·미션 삭제/비활성/기간 밖·집 비구성원·과거 날짜 완료)는 예외 대신 null 로 건너뛰어 완료 자체는 항상 성공하며, 반영 결과는 완료 응답의 `houseMissionContribution`(수행 체크 API 응답과 동일 형태)으로 내려간다. 완료 취소는 기여를 회수하지 않는다(프론트 정책과 동일).
- 이 연동 필드 추가는 프론트 요청 기반 확장이다. 계약이 확정되면 spec 정본(rougether-spec)에 반영한다.
