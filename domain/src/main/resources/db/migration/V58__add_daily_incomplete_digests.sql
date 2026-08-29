CREATE TABLE daily_incomplete_digests
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT      NOT NULL,
    digest_date     DATE        NOT NULL,
    routine_count   INT         NOT NULL,
    todo_count      INT         NOT NULL,
    notification_id BIGINT      NULL,
    push_status     VARCHAR(20) NOT NULL,
    sent_at         TIMESTAMP   NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_daily_incomplete_digests_user_date UNIQUE (user_id, digest_date),
    CONSTRAINT uk_daily_incomplete_digests_notification UNIQUE (notification_id),
    CONSTRAINT fk_daily_incomplete_digests_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_daily_incomplete_digests_notification FOREIGN KEY (notification_id) REFERENCES notification (id)
);

CREATE INDEX idx_daily_incomplete_digests_date_status_id
    ON daily_incomplete_digests (digest_date, push_status, id);

CREATE TABLE daily_incomplete_digest_targets
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    digest_id   BIGINT      NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id   BIGINT      NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_daily_incomplete_digest_targets_target UNIQUE (digest_id, target_type, target_id),
    CONSTRAINT fk_daily_incomplete_digest_targets_digest FOREIGN KEY (digest_id) REFERENCES daily_incomplete_digests (id)
);

CREATE INDEX idx_daily_incomplete_digest_targets_lookup
    ON daily_incomplete_digest_targets (target_type, target_id);
