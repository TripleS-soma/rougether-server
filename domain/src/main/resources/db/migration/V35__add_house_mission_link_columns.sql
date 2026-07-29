-- 집 단체미션 연동 표시를 서버 저장으로 전환한다.
-- 지금까지 클라이언트가 미션·루틴 이름 매칭으로 연동을 추적해 이름이 바뀌면 연동이 끊겼다.
-- 루틴은 연동된 단체미션 id, 카테고리는 연동된 집 id 를 보관하고 응답에 노출한다.
-- FK 는 걸지 않는다 — 미션/집이 soft delete 돼도 연동 값은 이력으로 남기고,
-- 유효 여부는 클라이언트가 보유한 미션·집 목록과 id 대조로 판별한다.

ALTER TABLE routines ADD COLUMN house_mission_id BIGINT NULL AFTER origin_routine_id;
ALTER TABLE categories ADD COLUMN house_id BIGINT NULL AFTER icon_key;
