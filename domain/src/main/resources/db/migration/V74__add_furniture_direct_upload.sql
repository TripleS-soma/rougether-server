ALTER TABLE furniture_generation_jobs ADD COLUMN raw_source_key VARCHAR(255) NULL;
ALTER TABLE furniture_generation_jobs ADD COLUMN source_sha256 VARCHAR(64) NULL;
ALTER TABLE furniture_generation_jobs ADD COLUMN source_bytes BIGINT NULL;
ALTER TABLE furniture_generation_jobs ADD COLUMN source_content_type VARCHAR(30) NULL;
ALTER TABLE furniture_generation_jobs ADD COLUMN upload_expires_at DATETIME(6) NULL;
ALTER TABLE furniture_generation_jobs ADD COLUMN source_version VARCHAR(1024) NULL;
ALTER TABLE furniture_generation_jobs ADD COLUMN processed_version VARCHAR(1024) NULL;
ALTER TABLE furniture_generation_jobs ADD COLUMN processed_sha256 VARCHAR(64) NULL;
