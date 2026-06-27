-- 어드민 계정 (issue #4 결정 = B: 유저와 완전 분리).
-- 유저(users / oauth_accounts)와 무관 — 소셜 로그인 미사용, 아이디 + 비밀번호.
-- 운영 공유 DB 에서는 user-api 가 이 migration 을 실행하고, admin-api 는 검증(validate)만 한다.

CREATE TABLE admin_users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(30)  NOT NULL DEFAULT 'ADMIN',
    created_at    TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);

-- 아이디는 로그인 식별자라 고유.
ALTER TABLE admin_users ADD CONSTRAINT uq_admin_username UNIQUE (username);
