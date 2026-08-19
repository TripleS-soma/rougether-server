CREATE TABLE asset_pipeline_jobs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    slug VARCHAR(100) NOT NULL,
    asset_kind VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    theme_code VARCHAR(100) NULL,
    source_asset_key VARCHAR(255) NULL,
    output_asset_key VARCHAR(255) NOT NULL,
    preview_asset_key VARCHAR(255) NULL,
    target_item_id BIGINT NULL,
    expected_old_asset_key VARCHAR(255) NULL,
    quality_score INT NULL,
    qa_passed BOOLEAN NOT NULL DEFAULT FALSE,
    created_by VARCHAR(50) NOT NULL,
    approved_by VARCHAR(50) NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_asset_pipeline_jobs_slug UNIQUE (slug),
    CONSTRAINT chk_asset_pipeline_jobs_quality_score CHECK (
        quality_score IS NULL OR quality_score BETWEEN 0 AND 100
    )
);

CREATE TABLE asset_pipeline_checks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    job_id BIGINT NOT NULL,
    check_code VARCHAR(60) NOT NULL,
    label VARCHAR(100) NOT NULL,
    result VARCHAR(20) NOT NULL,
    score INT NULL,
    is_required BOOLEAN NOT NULL DEFAULT TRUE,
    message VARCHAR(500) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_asset_pipeline_checks_job_code UNIQUE (job_id, check_code),
    CONSTRAINT fk_asset_pipeline_checks_job FOREIGN KEY (job_id)
        REFERENCES asset_pipeline_jobs (id) ON DELETE CASCADE,
    CONSTRAINT chk_asset_pipeline_checks_score CHECK (score IS NULL OR score BETWEEN 0 AND 100)
);

CREATE TABLE asset_pipeline_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    job_id BIGINT NOT NULL,
    from_status VARCHAR(30) NULL,
    to_status VARCHAR(30) NOT NULL,
    actor VARCHAR(50) NOT NULL,
    note VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_asset_pipeline_events_job FOREIGN KEY (job_id)
        REFERENCES asset_pipeline_jobs (id) ON DELETE CASCADE
);

CREATE INDEX idx_asset_pipeline_jobs_status_updated
    ON asset_pipeline_jobs (status, updated_at);
CREATE INDEX idx_asset_pipeline_events_job_created
    ON asset_pipeline_events (job_id, created_at);
