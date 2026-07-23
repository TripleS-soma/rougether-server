-- 사용자별 알림 설정. 행이 없으면 ON 이 기본값이라 off 로 바꿀 때만 행이 생긴다(신규 가입자는 행 0개).
-- type 은 개별 NotificationType 이 아니라 설정 그룹(NotificationSettingType: ALL/REMINDER/HOUSE).
-- DB: MySQL 8 (운영) / H2 MySQL 모드 (로컬·테스트) 양립.

CREATE TABLE notification_setting (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    type       VARCHAR(30) NOT NULL,
    enabled    BOOLEAN     NOT NULL,
    created_at TIMESTAMP   NOT NULL,
    updated_at TIMESTAMP   NOT NULL,
    PRIMARY KEY (id)
);

ALTER TABLE notification_setting ADD CONSTRAINT fk_notification_setting_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE notification_setting ADD CONSTRAINT uk_notification_setting_user_type UNIQUE (user_id, type);
