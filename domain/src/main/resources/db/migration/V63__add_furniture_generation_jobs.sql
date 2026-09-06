-- 사용자 사진을 가구로 변환하는 durable 작업. 원본/후보 key는 공개 CDN prefix에 두지 않음.
CREATE TABLE furniture_generation_jobs (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    input_digest VARCHAR(64) NOT NULL,
    target_hint VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL,
    action VARCHAR(20) NOT NULL,
    source_key VARCHAR(255),
    candidate_key VARCHAR(255),
    result_asset_key VARCHAR(255),
    user_item_id BIGINT,
    feedback VARCHAR(500) NOT NULL,
    correction_prompt VARCHAR(2000) NOT NULL,
    last_decision VARCHAR(30),
    last_reason VARCHAR(1000),
    failure_code VARCHAR(50),
    image_attempts INT NOT NULL DEFAULT 0,
    review_attempts INT NOT NULL DEFAULT 0,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    lease_token VARCHAR(36),
    lease_until TIMESTAMP(6),
    next_run_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_furniture_job_request UNIQUE (user_id, request_id),
    CONSTRAINT uk_furniture_job_inventory UNIQUE (user_item_id),
    CONSTRAINT fk_furniture_job_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_furniture_job_inventory FOREIGN KEY (user_item_id) REFERENCES user_items(id)
);
CREATE INDEX idx_furniture_job_due ON furniture_generation_jobs(status, next_run_at, lease_until);
CREATE INDEX idx_furniture_job_owner ON furniture_generation_jobs(user_id, created_at);
CREATE INDEX idx_furniture_job_expiry ON furniture_generation_jobs(expires_at);

CREATE TABLE furniture_generation_feedback (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    content VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_furniture_feedback_request UNIQUE (job_id, request_id),
    CONSTRAINT fk_furniture_feedback_job FOREIGN KEY (job_id) REFERENCES furniture_generation_jobs(id)
);

-- 사용자가 만든 가구는 개인 보관함/방에만 노출하고 상점·뽑기 풀에 자동 등록하지 않음.
INSERT INTO themes(code, name, cover_image_key, is_active)
VALUES ('photo_furniture', '사진으로 만든 가구', NULL, FALSE);
