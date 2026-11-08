-- 관리자 공지 초안과 고정된 수신 대상. 발송 승인은 초안과 분리함.
CREATE TABLE notification_campaigns (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_by_admin_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    audience VARCHAR(20) NOT NULL,
    push_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL,
    recipient_count INT NOT NULL,
    started_at TIMESTAMP(6) NULL,
    approved_by_username VARCHAR(50) NULL,
    completed_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_notification_campaign_request UNIQUE (created_by_admin_id, request_id),
    CONSTRAINT fk_notification_campaign_admin FOREIGN KEY (created_by_admin_id) REFERENCES admin_users (id)
);
CREATE INDEX idx_notification_campaign_status ON notification_campaigns (status, id);

CREATE TABLE notification_campaign_recipients (
    id BIGINT NOT NULL AUTO_INCREMENT,
    campaign_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    notification_id BIGINT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_notification_campaign_user UNIQUE (campaign_id, user_id),
    CONSTRAINT uk_notification_campaign_notification UNIQUE (notification_id),
    CONSTRAINT fk_notification_campaign_recipient_campaign FOREIGN KEY (campaign_id) REFERENCES notification_campaigns (id),
    -- 회원탈퇴의 알림 hard delete를 막지 않고 발송 대상 이력만 유지함.
    CONSTRAINT fk_notification_campaign_recipient_notification FOREIGN KEY (notification_id) REFERENCES notification (id) ON DELETE SET NULL
);
CREATE INDEX idx_notification_campaign_recipient_pending ON notification_campaign_recipients (campaign_id, status, id);
