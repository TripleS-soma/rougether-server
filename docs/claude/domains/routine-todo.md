# 루틴 / 투두 도메인 (서버 구현 노트)

도메인 계약(기능·API·데이터)의 **정본은 spec repo**에 있습니다. 이 문서와 어긋나면 spec이 우선입니다.

- 정본: [rougether-spec](https://github.com/TripleS-soma/rougether-spec) repo의 `domains/routine-todo/` (prd.md / features.md / api.md)
- 전체 ERD: 같은 repo `erd.md`
- API 공통 규약: 같은 repo `api.md`

이 문서는 이 서버 repo에서의 **구현 노트**(Spring 패키지 구조, 트랜잭션 경계, 서버 특이사항)만 둡니다.

## 구현 노트

- 투두(`todos`)는 `due_date`(마감일)와 별개로 `due_time`(마감 시각, `LocalTime`, nullable) 컬럼을 가진다. 루틴의 `scheduled_time`과 동일하게 초/나노를 0으로 정규화해 저장한다. 등록·수정(`TodoCreateRequest`/`TodoUpdateRequest`)에서 입력받고, 단건·목록·오늘 현황·캘린더 응답(`TodoResponse`/`TodayTodoItem`)에 노출한다. 완료 가능 여부·보상 판정은 기존대로 `due_date`(날짜 단위)만 기준으로 하며 `due_time`은 판정에 관여하지 않는다.
- 오늘 현황·캘린더에서 같은 날짜로 묶인 투두는 루틴의 `scheduled_time` 정렬과 동일하게 `due_time` 오름차순(시각 없는 항목은 뒤로)으로 정렬한 뒤 id 순으로 둔다(`DailyAgendaAssembler`).
