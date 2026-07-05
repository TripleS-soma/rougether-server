-- 루틴 시간버전(temporal versioning) 계보 루트. 반복 스케줄을 바꾸면 옛 버전을 닫고
-- 새 버전 row를 만드는데, 목록·정렬을 계보당 하나로 묶으려면 계보 루트 id가 필요함.
-- nullable FK: 생성 직후 자기 id로 채워지므로 상시 값이 있으나, 백필/신규 삽입 순간을 위해 NULL 허용.
ALTER TABLE routines ADD COLUMN origin_routine_id BIGINT NULL;

-- 기존 row는 각자 계보 루트 → 자기 id로 백필. 같은 트랜잭션 안에서 컬럼 추가 직후 실행됨.
UPDATE routines SET origin_routine_id = id WHERE origin_routine_id IS NULL;

-- 목록·과거 조회가 (user_id, origin_routine_id)로 계보를 묶어 조회함
CREATE INDEX idx_routines_user_origin ON routines (user_id, origin_routine_id);
