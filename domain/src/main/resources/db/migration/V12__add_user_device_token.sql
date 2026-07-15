-- FCM 발송 대상 디바이스 토큰. 사용자당 N개 허용(멀티디바이스), token은 기기 단위로 유일함.
-- DB: MySQL 8 (운영) / H2 MySQL 모드 (로컬·테스트) 양립.

CREATE TABLE user_device_token (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    token      VARCHAR(255) NOT NULL,
    platform   VARCHAR(20)  NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);

ALTER TABLE user_device_token ADD CONSTRAINT uq_user_device_token_token UNIQUE (token);
ALTER TABLE user_device_token ADD CONSTRAINT fk_user_device_token_user FOREIGN KEY (user_id) REFERENCES users (id);
CREATE INDEX idx_user_device_token_user ON user_device_token (user_id);
