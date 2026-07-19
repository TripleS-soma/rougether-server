-- 마지막 접속 시각을 로그인뿐 아니라 refresh 재발급 시에도 갱신하도록 의미가 바뀜.
-- "마지막 로그인 시각" → "마지막 토큰 발급 시각(마지막 접속)" 의미 변화에 맞춰 컬럼명을 rename 한다.

ALTER TABLE users RENAME COLUMN last_login_at TO last_accessed_at;
