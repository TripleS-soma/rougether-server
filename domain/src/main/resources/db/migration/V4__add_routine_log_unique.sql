-- 당일 중복 완료 DB 방어. 같은 루틴+날짜로 routine_logs 한 건만 허용.
-- (앱 레이어 사전 조회 guard와 이중 방어. 취소는 row hard delete라 취소 후 재완료는 정상.)
-- 기존 idx_routine_logs_routine_date 인덱스는 조회용으로 유지(중복이지만 무해).

ALTER TABLE routine_logs ADD CONSTRAINT uq_routine_log_routine_date UNIQUE (routine_id, routine_date);
