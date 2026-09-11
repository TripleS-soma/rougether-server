-- 자동 실행하지 않음. API 업로드와 실행 접수를 잠시 멈추고 기존 작업을 drain한 뒤 적용.
-- 구버전 워커는 execution_mode를 모르므로 반드시 먼저 종료해야 함.
START TRANSACTION;
SELECT * FROM furniture_worker_capacity WHERE id = 1 FOR UPDATE;
-- 아래 결과가 모두 0인지 먼저 확인. 0이 아니면 ROLLBACK하고 작업 종료를 기다림.
SELECT status, COUNT(*) FROM furniture_generation_jobs
WHERE status IN ('UPLOADING', 'QUEUED', 'PROCESSING') GROUP BY status;
-- 수동 확인 후 실행할 문장. 기본 스크립트 실행만으로는 모드를 바꾸지 않음.
-- UPDATE furniture_worker_capacity SET execution_mode='LAMBDA', max_in_flight=2, execution_enabled=TRUE WHERE id=1;
ROLLBACK;
