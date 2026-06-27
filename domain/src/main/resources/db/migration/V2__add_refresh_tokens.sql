-- refresh 토큰 회전(RTR) 저장소. 원문이 아니라 해시만 저장함.
-- DB: MySQL 8 (운영) / H2 MySQL 모드 (로컬·테스트) 양립.

CREATE TABLE refresh_tokens (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP    NOT NULL,
    revoked_at TIMESTAMP    NULL,
    created_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);

ALTER TABLE refresh_tokens ADD CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash);
ALTER TABLE refresh_tokens ADD CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users (id);
CREATE INDEX idx_refresh_user ON refresh_tokens (user_id);
