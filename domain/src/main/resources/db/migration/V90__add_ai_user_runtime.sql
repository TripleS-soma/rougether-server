-- 가상 사용자는 동거 봇과 별도이며 실사용자 KPI에서 제외함.
ALTER TABLE users ADD COLUMN is_ai BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD CONSTRAINT ck_users_ai_not_bot CHECK (NOT (is_ai AND is_bot));
CREATE TABLE ai_user_profiles (
 user_id BIGINT PRIMARY KEY, ai_key VARCHAR(40) NOT NULL UNIQUE,
 persona TEXT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 next_run_at TIMESTAMP(6) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_ai_profile_user FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE TABLE ai_user_jobs (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,
 job_key VARCHAR(160) NOT NULL UNIQUE, priority INT NOT NULL,
 source_type VARCHAR(20) NOT NULL, source_id BIGINT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING', ready_at TIMESTAMP(6) NOT NULL,
 attempts INT NOT NULL DEFAULT 0, lease_owner VARCHAR(80) NULL, fencing_token BIGINT NULL, result_text TEXT NULL,
 context_sources JSON NULL,
 created_at TIMESTAMP(6) NOT NULL, finished_at TIMESTAMP(6) NULL,
 CONSTRAINT fk_ai_job_user FOREIGN KEY (user_id) REFERENCES ai_user_profiles(user_id)
);
CREATE INDEX ix_ai_jobs_due ON ai_user_jobs(status, ready_at, priority, id);
CREATE TABLE ai_user_runtime (
 id INT PRIMARY KEY, current_job_id BIGINT NULL, lease_owner VARCHAR(80) NULL,
 fencing_token BIGINT NOT NULL DEFAULT 0, lease_until TIMESTAMP(6) NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO ai_user_runtime(id) VALUES (1);
CREATE TABLE ai_user_memories (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,
 job_id BIGINT NOT NULL UNIQUE, kind VARCHAR(20) NOT NULL,
 content TEXT NOT NULL, source_type VARCHAR(20) NOT NULL, source_id BIGINT NULL,
 created_at TIMESTAMP(6) NOT NULL, expires_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_ai_memory_user FOREIGN KEY (user_id) REFERENCES ai_user_profiles(user_id),
 CONSTRAINT fk_ai_memory_job FOREIGN KEY (job_id) REFERENCES ai_user_jobs(id)
);
CREATE INDEX ix_ai_memories_lookup ON ai_user_memories(user_id, expires_at, id);
