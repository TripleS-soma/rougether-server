-- 이벤트 추가 코인이 기존 일일 원금 한도와 방 성장에 섞이지 않도록 원금을 보존함.
-- 구버전 서버의 새 행은 NULL로 남아 조회 시 기존 reward_amount를 원금으로 해석함.
ALTER TABLE routine_logs ADD COLUMN base_reward_amount INT NULL DEFAULT NULL;
ALTER TABLE todos ADD COLUMN base_reward_amount INT NULL DEFAULT NULL;
