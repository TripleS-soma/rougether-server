-- 장기 기억 자체는 Neo4j에 저장하고, 업무 커밋과 기억 반영 사이의 재시도 상태만 보관함.
ALTER TABLE ai_user_jobs ADD COLUMN action_name VARCHAR(40) NULL;
ALTER TABLE ai_user_jobs ADD COLUMN action_payload JSON NULL;
ALTER TABLE ai_user_jobs ADD COLUMN memory_sources JSON NULL;
ALTER TABLE ai_user_jobs ADD COLUMN memory_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE ai_user_jobs ADD COLUMN memory_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE ai_user_jobs ADD COLUMN memory_ready_at TIMESTAMP(6) NULL;
CREATE INDEX ix_ai_jobs_memory ON ai_user_jobs(status, memory_status, memory_ready_at, id);
