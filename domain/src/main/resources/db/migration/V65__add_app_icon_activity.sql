-- 앱의 명시적 foreground 활동만 기록함. 기존 로그인·refresh 시각으로 backfill하지 않음.
CREATE TABLE user_app_activity (
    user_id BIGINT NOT NULL,
    last_foreground_at TIMESTAMP(6) NOT NULL,
    last_notified_stage INT NOT NULL DEFAULT 0,
    last_notification_id BIGINT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_app_activity_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_user_app_activity_stage CHECK (last_notified_stage BETWEEN 0 AND 3)
);

CREATE INDEX idx_user_app_activity_foreground ON user_app_activity (last_foreground_at, user_id);
