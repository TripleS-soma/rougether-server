-- 기본값은 기존 상주 워커. 진행 중 작업을 비운 뒤 운영자가 LAMBDA로 전환함.
ALTER TABLE furniture_worker_capacity ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'RESIDENT';
ALTER TABLE furniture_generation_jobs ADD COLUMN execution_id VARCHAR(36) NULL;
CREATE TABLE furniture_lambda_execution (
    id VARCHAR(36) PRIMARY KEY,
    job_id VARCHAR(36) NOT NULL,
    state VARCHAR(16) NOT NULL,
    owner VARCHAR(100) NULL,
    deadline TIMESTAMP(6) NULL,
    sequence INT NOT NULL,
    command_hash VARCHAR(64) NULL,
    reply_json TEXT NULL,
    publish_token VARCHAR(36) NULL,
    publish_after TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_furniture_lambda_job FOREIGN KEY (job_id) REFERENCES furniture_generation_jobs(id) ON DELETE CASCADE,
    INDEX idx_furniture_lambda_publish (state, publish_after, created_at)
);
