-- 알림 내역. NotificationService.send(...)가 push 성패와 무관하게 항상 저장(best-effort push).
-- DB: MySQL 8 (운영) / H2 MySQL 모드 (로컬·테스트) 양립.

CREATE TABLE notification (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    type       VARCHAR(30)  NOT NULL,
    title      VARCHAR(255) NOT NULL,
    body       VARCHAR(1000) NOT NULL,
    ref_id     BIGINT       NULL,
    is_read    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);

ALTER TABLE notification ADD CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES users (id);
CREATE INDEX idx_notification_user_id ON notification (user_id, id);
