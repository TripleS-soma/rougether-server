# 루틴 / 투두 도메인 (서버 구현 노트)

도메인 계약(기능·API·데이터)의 **정본은 spec repo**에 있습니다. 이 문서와 어긋나면 spec이 우선입니다.

- 정본: [rougether-spec](https://github.com/TripleS-soma/rougether-spec) repo의 `domains/routine-todo/` (prd.md / features.md / api.md)
- 전체 ERD: 같은 repo `erd.md`
- API 공통 규약: 같은 repo `api.md`

이 문서는 이 서버 repo에서의 **구현 노트**(Spring 패키지 구조, 트랜잭션 경계, 서버 특이사항)만 둡니다.

## 구현 노트

- 투두(`todos`)는 `due_date`(마감일)와 별개로 `due_time`(마감 시각, `LocalTime`, nullable) 컬럼을 가진다. 루틴의 `scheduled_time`과 동일하게 초/나노를 0으로 정규화해 저장한다. 등록·수정(`TodoCreateRequest`/`TodoUpdateRequest`)에서 입력받고, 단건·목록·오늘 현황·캘린더 응답(`TodoResponse`/`TodayTodoItem`)에 노출한다. 완료 가능 여부·보상 판정은 기존대로 `due_date`(날짜 단위)만 기준으로 하며 `due_time`은 판정에 관여하지 않는다.
- 오늘 현황·캘린더에서 같은 날짜로 묶인 투두는 루틴의 `scheduled_time` 정렬과 동일하게 `due_time` 오름차순(시각 없는 항목은 뒤로)으로 정렬한 뒤 id 순으로 둔다(`DailyAgendaAssembler`).

### 집 단체미션 연동 표시 (V35)

- 클라이언트가 단체미션↔루틴 연동을 이름 매칭으로 추적하던 것을 서버 저장 식별자로 대체한다(이름이 바뀌면 연동이 끊기는 문제, 프론트 요청 2026-07-29).
- 루틴은 `routines.house_mission_id`(nullable)에 연동된 단체미션 id, 카테고리는 `categories.house_id`(nullable)에 연동된 집 id를 보관하고 등록·수정 요청(`houseMissionId`/`houseId`)으로 받는다. 단건·목록·오늘 현황·캘린더 응답(`RoutineResponse`/`TodayRoutineItem`/`CategoryResponse`)에 노출한다.
- 연동 지정은 해당 집의 ACTIVE 구성원만 가능하다(`HouseLinkValidator`, 집 도메인 소유 컴포넌트). 위반 시 `HOUSE_MISSION_NOT_FOUND`/`HOUSE_NOT_FOUND`/`HOUSE_NOT_MEMBER`.
- 수정 요청에서 이 필드는 null=기존 연동 유지다. `categoryId`(null=해제)와 규칙이 다른 이유: 연동 필드를 모르는 구버전 클라이언트의 수정 요청이 연동을 지우면 안 되기 때문. 해제는 전용 API 로 한다 — `DELETE /api/v1/routines/{id}/house-mission-link`, `DELETE /api/v1/categories/{id}/house-link` (멱등, 대상 데이터 자체는 유지, 과거 자동 기여는 미회수).
- 연동은 집 이벤트 시 서버가 자동 해제한다: 미션 삭제 → 전 구성원의 연동 루틴 해제(`clearHouseMissionLink`), 집 탈퇴·강퇴 → 그 회원의 해당 집 연동 루틴·카테고리 해제(`clearHouseMissionLinksOfMember`/`clearHouseLinkOfMember`, 마지막 구성원 탈퇴로 집이 정리되는 경우 포함). 루틴·카테고리 자체는 개인 데이터라 서버가 삭제하지 않는다(삭제 여부는 클라이언트 UX 결정). bulk 해제는 영속성 컨텍스트를 우회하고 비우지도 않으므로(잠근 house 등이 detach 되면 변경 유실), 같은 트랜잭션에서 이후 루틴·카테고리를 읽지 않는 위치(트랜잭션 끝)에서만 호출한다.
- FK 없이 식별자만 보관한다. 자동 해제가 커버하지 못하는 순간(동시성 등)에 낡은 id 가 보이면 클라이언트는 보유 목록에 없는 id 를 미연동으로 취급한다. 루틴 버전 분기(`copyAsNewVersion`) 시 연동은 새 버전으로 승계된다.
- 연동 루틴을 오늘(KST) 날짜로 완료하면 해당 미션에 수행 체크가 자동 반영된다(`RoutineLogService` → `HouseMissionService.autoContribute`, 완료와 한 트랜잭션). 기여 불가 사유(오늘 이미 기여·미션 삭제/비활성/기간 밖·집 비구성원·과거 날짜 완료)는 예외 대신 null 로 건너뛰어 완료 자체는 항상 성공하며, 반영 결과는 완료 응답의 `houseMissionContribution`(수행 체크 API 응답과 동일 형태)으로 내려간다. 완료 취소는 기여를 회수하지 않는다(프론트 정책과 동일).
- 루틴·투두·카테고리 도메인은 2026-08-01 임채영 팀 이탈(취업)로 장진형 담당으로 이관됐다. 이 연동 필드 추가에 걸려 있던 "계약 확정 전 담당자 확인" open question은 이관으로 해소됐고, 계약 확정 여부는 spec repo(open-questions.md)에서 계속 관리한다.
