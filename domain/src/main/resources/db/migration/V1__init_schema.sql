-- Rougether MVP 초기 스키마.
-- 출처: ERDCloud "루게더 mvp (최종)" + rougether-spec/erd.md 확정 결정 반영.
-- DB: MySQL 8 (운영) / H2 MySQL 모드 (로컬·테스트). 두 환경 모두에서 실행되도록
-- ENGINE/CHARSET 절은 생략하고(서버 기본 InnoDB·utf8mb4 의존) AUTO_INCREMENT를 사용함.

-- ============================================================
-- 회원 / 인증 / 재화
-- ============================================================

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    nickname      VARCHAR(30)  NULL,
    last_login_at TIMESTAMP    NULL,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL,
    deleted_at    TIMESTAMP    NULL,
    PRIMARY KEY (id)
);

-- 소셜 로그인 수단은 users와 분리. 한 user가 여러 provider 연결 가능.
CREATE TABLE oauth_accounts (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          BIGINT       NOT NULL,
    provider         VARCHAR(20)  NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    created_at       TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE user_wallets (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       BIGINT      NOT NULL,
    currency_type VARCHAR(30) NOT NULL,
    balance       INT         NOT NULL,
    created_at    TIMESTAMP   NOT NULL,
    updated_at    TIMESTAMP   NOT NULL,
    PRIMARY KEY (id)
);

-- ============================================================
-- 캐릭터 / 목표 (온보딩)
-- ============================================================

CREATE TABLE characters (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    code           VARCHAR(50)  NOT NULL,
    name           VARCHAR(100) NOT NULL,
    base_asset_key VARCHAR(255) NOT NULL,
    sort_order     INT          NOT NULL,
    is_active      BOOLEAN      NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE user_characters (
    id           BIGINT    NOT NULL AUTO_INCREMENT,
    user_id      BIGINT    NOT NULL,
    character_id BIGINT    NOT NULL,
    is_selected  BOOLEAN   NOT NULL,
    acquired_at  TIMESTAMP NOT NULL,
    created_at   TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP NOT NULL,
    deleted_at   TIMESTAMP NULL,
    PRIMARY KEY (id)
);

CREATE TABLE goals (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    code       VARCHAR(50)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    sort_order INT          NOT NULL,
    is_active  BOOLEAN      NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE user_goals (
    id         BIGINT    NOT NULL AUTO_INCREMENT,
    user_id    BIGINT    NOT NULL,
    goal_id    BIGINT    NOT NULL,
    is_primary BOOLEAN   NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id)
);

-- ============================================================
-- 카테고리 / 루틴 / 투두
-- ============================================================

-- 공개 범위는 카테고리 단위(visibility). routines에는 visibility를 두지 않음.
CREATE TABLE categories (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    name       VARCHAR(100) NOT NULL,
    color_hex  VARCHAR(20)  NULL,
    visibility VARCHAR(30)  NULL,
    icon_key   VARCHAR(100) NULL,
    sort_order INT          NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL,
    deleted_at TIMESTAMP    NULL,
    PRIMARY KEY (id)
);

CREATE TABLE routines (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    category_id    BIGINT       NULL,
    title          VARCHAR(160) NOT NULL,
    auth_type      VARCHAR(30)  NOT NULL,
    status         VARCHAR(30)  NOT NULL DEFAULT 'active',
    repeat_type    VARCHAR(40)  NULL,
    repeat_days    JSON         NULL,
    scheduled_time TIME         NULL,
    starts_on      DATE         NULL,
    ends_on        DATE         NULL,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    deleted_at     TIMESTAMP    NULL,
    PRIMARY KEY (id)
);

CREATE TABLE routine_logs (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    routine_id           BIGINT      NOT NULL,
    routine_date         DATE        NOT NULL,
    status               VARCHAR(30) NOT NULL,
    completed_at         TIMESTAMP   NULL,
    reward_currency_type VARCHAR(30) NULL,
    reward_amount        INT         NOT NULL,
    created_at           TIMESTAMP   NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE photo_verifications (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    routine_log_id   BIGINT       NOT NULL,
    storage_key      VARCHAR(255) NOT NULL,
    privacy_scope    VARCHAR(30)  NOT NULL DEFAULT 'private',
    ai_review_status VARCHAR(30)  NOT NULL DEFAULT 'pending',
    uploaded_at      TIMESTAMP    NOT NULL,
    deleted_at       TIMESTAMP    NULL,
    PRIMARY KEY (id)
);

CREATE TABLE todos (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    user_id              BIGINT       NOT NULL,
    category_id          BIGINT       NULL,
    title                VARCHAR(160) NOT NULL,
    description          TEXT         NULL,
    due_date             DATE         NULL,
    status               VARCHAR(30)  NOT NULL DEFAULT 'pending',
    completed_at         TIMESTAMP    NULL,
    reward_currency_type VARCHAR(30)  NULL,
    reward_amount        INT          NOT NULL,
    created_at           TIMESTAMP    NOT NULL,
    updated_at           TIMESTAMP    NOT NULL,
    deleted_at           TIMESTAMP    NULL,
    PRIMARY KEY (id)
);

CREATE TABLE streaks (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    user_id             BIGINT      NOT NULL,
    current_count       INT         NOT NULL,
    longest_count       INT         NOT NULL,
    last_success_date   DATE        NULL,
    last_evaluated_date DATE        NULL,
    status              VARCHAR(30) NOT NULL DEFAULT 'active',
    updated_at          TIMESTAMP   NOT NULL,
    PRIMARY KEY (id)
);

-- ============================================================
-- 방 (개인)
-- ============================================================

-- user_id가 PK이자 users 1:1 (별도 id 없음).
CREATE TABLE personal_rooms (
    user_id      BIGINT    NOT NULL,
    growth_level INT       NOT NULL,
    updated_at   TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id)
);

CREATE TABLE room_surface_slots (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    room_user_id BIGINT      NOT NULL,
    slot_type    VARCHAR(40) NOT NULL,
    user_item_id BIGINT      NULL,
    saved_at     TIMESTAMP   NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE room_guestbooks (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    content       VARCHAR(500) NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    deleted_at    TIMESTAMP    NULL,
    room_owner_id BIGINT       NOT NULL,
    house_id      BIGINT       NOT NULL,
    author_id     BIGINT       NOT NULL,
    PRIMARY KEY (id)
);

-- ============================================================
-- 상점 / 아이템 / 테마 / 뽑기
-- ============================================================

CREATE TABLE themes (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    code            VARCHAR(50)  NOT NULL,
    name            VARCHAR(100) NOT NULL,
    cover_image_key VARCHAR(255) NULL,
    is_active       BOOLEAN      NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE items (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    theme_id               BIGINT       NOT NULL,
    category_code          VARCHAR(50)  NOT NULL,
    placement_type         VARCHAR(40)  NOT NULL,
    surface_slot_type      VARCHAR(40)  NULL,
    character_slot_type    VARCHAR(40)  NULL,
    name                   VARCHAR(120) NOT NULL,
    purchase_currency_type VARCHAR(30)  NULL,
    price_amount           INT          NULL,
    asset_key              VARCHAR(255) NOT NULL,
    is_limited             BOOLEAN      NOT NULL,
    is_active              BOOLEAN      NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE user_items (
    id          BIGINT    NOT NULL AUTO_INCREMENT,
    user_id     BIGINT    NOT NULL,
    item_id     BIGINT    NOT NULL,
    acquired_at TIMESTAMP NOT NULL,
    deleted_at  TIMESTAMP NULL,
    PRIMARY KEY (id)
);

CREATE TABLE gacha (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    code               VARCHAR(50)  NOT NULL,
    name               VARCHAR(120) NOT NULL,
    cost_currency_type VARCHAR(30)  NULL,
    cost_amount        INT          NOT NULL,
    draw_count         INT          NOT NULL,
    starts_at          TIMESTAMP    NULL,
    ends_at            TIMESTAMP    NULL,
    is_active          BOOLEAN      NOT NULL,
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL,
    theme_id           BIGINT       NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE gacha_pool_entries (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    gacha_id      BIGINT      NOT NULL,
    reward_type   VARCHAR(30) NOT NULL,
    item_id       BIGINT      NULL,
    currency_type VARCHAR(30) NULL,
    reward_amount INT         NULL,
    rarity        VARCHAR(30) NULL,
    weight        INT         NOT NULL,
    is_active     BOOLEAN     NOT NULL,
    PRIMARY KEY (id)
);

-- ============================================================
-- 집 (공동)
-- ============================================================

CREATE TABLE house (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    owner_user_id        BIGINT       NOT NULL,
    name                 VARCHAR(120) NOT NULL,
    description          TEXT         NULL,
    cover_image_key      VARCHAR(255) NULL,
    max_members          INT          NULL,
    current_member_count INT          NOT NULL,
    level                INT          NOT NULL,
    growth_points        INT          NOT NULL,
    invite_code          VARCHAR(50)  NULL,
    invite_expires_at    TIMESTAMP    NULL,
    created_at           TIMESTAMP    NOT NULL,
    updated_at           TIMESTAMP    NOT NULL,
    deleted_at           TIMESTAMP    NULL,
    PRIMARY KEY (id)
);

CREATE TABLE house_members (
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    house_id  BIGINT      NOT NULL,
    user_id   BIGINT      NOT NULL,
    role      VARCHAR(30) NOT NULL DEFAULT 'member',
    status    VARCHAR(30) NOT NULL DEFAULT 'active',
    joined_at TIMESTAMP   NOT NULL,
    left_at   TIMESTAMP   NULL,
    PRIMARY KEY (id)
);

CREATE TABLE house_goals (
    id       BIGINT NOT NULL AUTO_INCREMENT,
    house_id BIGINT NOT NULL,
    goal_id  BIGINT NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE house_missions (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    house_id     BIGINT       NOT NULL,
    title        VARCHAR(160) NOT NULL,
    mission_type VARCHAR(50)  NOT NULL,
    target_value INT          NOT NULL,
    status       VARCHAR(30)  NOT NULL DEFAULT 'active',
    starts_at    TIMESTAMP    NULL,
    ends_at      TIMESTAMP    NULL,
    created_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE house_mission_participants (
    id                 BIGINT    NOT NULL AUTO_INCREMENT,
    mission_id         BIGINT    NOT NULL,
    membership_id      BIGINT    NOT NULL,
    contribution_value INT       NOT NULL,
    reward_claimed     BOOLEAN   NOT NULL,
    updated_at         TIMESTAMP NOT NULL,
    PRIMARY KEY (id)
);

-- ============================================================
-- Unique 제약 (spec 확정 결정)
-- ============================================================

-- 같은 소셜 계정 중복 연결 차단.
ALTER TABLE oauth_accounts ADD CONSTRAINT uq_oauth_provider_user UNIQUE (provider, provider_user_id);
-- 같은 집 중복 가입만 차단(user_id 단독 unique는 두지 않음 — 다중 집 가입 허용).
ALTER TABLE house_members ADD CONSTRAINT uq_house_member UNIQUE (house_id, user_id);

-- ============================================================
-- Foreign keys
-- ============================================================

ALTER TABLE oauth_accounts ADD CONSTRAINT fk_oauth_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE user_wallets ADD CONSTRAINT fk_wallet_user FOREIGN KEY (user_id) REFERENCES users (id);

ALTER TABLE user_characters ADD CONSTRAINT fk_uchar_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE user_characters ADD CONSTRAINT fk_uchar_character FOREIGN KEY (character_id) REFERENCES characters (id);

ALTER TABLE user_goals ADD CONSTRAINT fk_ugoal_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE user_goals ADD CONSTRAINT fk_ugoal_goal FOREIGN KEY (goal_id) REFERENCES goals (id);

ALTER TABLE categories ADD CONSTRAINT fk_category_user FOREIGN KEY (user_id) REFERENCES users (id);

ALTER TABLE routines ADD CONSTRAINT fk_routine_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE routines ADD CONSTRAINT fk_routine_category FOREIGN KEY (category_id) REFERENCES categories (id);

ALTER TABLE routine_logs ADD CONSTRAINT fk_rlog_routine FOREIGN KEY (routine_id) REFERENCES routines (id);
ALTER TABLE photo_verifications ADD CONSTRAINT fk_photo_rlog FOREIGN KEY (routine_log_id) REFERENCES routine_logs (id);

ALTER TABLE todos ADD CONSTRAINT fk_todo_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE todos ADD CONSTRAINT fk_todo_category FOREIGN KEY (category_id) REFERENCES categories (id);

ALTER TABLE streaks ADD CONSTRAINT fk_streak_user FOREIGN KEY (user_id) REFERENCES users (id);

ALTER TABLE personal_rooms ADD CONSTRAINT fk_proom_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE room_surface_slots ADD CONSTRAINT fk_slot_proom FOREIGN KEY (room_user_id) REFERENCES personal_rooms (user_id);
ALTER TABLE room_surface_slots ADD CONSTRAINT fk_slot_uitem FOREIGN KEY (user_item_id) REFERENCES user_items (id);

ALTER TABLE room_guestbooks ADD CONSTRAINT fk_gbook_owner FOREIGN KEY (room_owner_id) REFERENCES users (id);
ALTER TABLE room_guestbooks ADD CONSTRAINT fk_gbook_house FOREIGN KEY (house_id) REFERENCES house (id);
ALTER TABLE room_guestbooks ADD CONSTRAINT fk_gbook_author FOREIGN KEY (author_id) REFERENCES users (id);

ALTER TABLE items ADD CONSTRAINT fk_item_theme FOREIGN KEY (theme_id) REFERENCES themes (id);
ALTER TABLE user_items ADD CONSTRAINT fk_uitem_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE user_items ADD CONSTRAINT fk_uitem_item FOREIGN KEY (item_id) REFERENCES items (id);

ALTER TABLE gacha ADD CONSTRAINT fk_gacha_theme FOREIGN KEY (theme_id) REFERENCES themes (id);
ALTER TABLE gacha_pool_entries ADD CONSTRAINT fk_gpool_gacha FOREIGN KEY (gacha_id) REFERENCES gacha (id);
ALTER TABLE gacha_pool_entries ADD CONSTRAINT fk_gpool_item FOREIGN KEY (item_id) REFERENCES items (id);

ALTER TABLE house ADD CONSTRAINT fk_house_owner FOREIGN KEY (owner_user_id) REFERENCES users (id);
ALTER TABLE house_members ADD CONSTRAINT fk_hmember_house FOREIGN KEY (house_id) REFERENCES house (id);
ALTER TABLE house_members ADD CONSTRAINT fk_hmember_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE house_goals ADD CONSTRAINT fk_hgoal_house FOREIGN KEY (house_id) REFERENCES house (id);
ALTER TABLE house_goals ADD CONSTRAINT fk_hgoal_goal FOREIGN KEY (goal_id) REFERENCES goals (id);
ALTER TABLE house_missions ADD CONSTRAINT fk_hmission_house FOREIGN KEY (house_id) REFERENCES house (id);
ALTER TABLE house_mission_participants ADD CONSTRAINT fk_hmp_mission FOREIGN KEY (mission_id) REFERENCES house_missions (id);
ALTER TABLE house_mission_participants ADD CONSTRAINT fk_hmp_member FOREIGN KEY (membership_id) REFERENCES house_members (id);

-- ============================================================
-- 조회 인덱스 (FK 단일 컬럼은 MySQL이 자동 인덱싱하므로 핫 경로 복합 인덱스만 보강)
-- ============================================================

CREATE INDEX idx_routine_logs_routine_date ON routine_logs (routine_id, routine_date);
CREATE INDEX idx_routines_user_status ON routines (user_id, status);
CREATE INDEX idx_todos_user_status ON todos (user_id, status);
CREATE INDEX idx_user_wallets_user_currency ON user_wallets (user_id, currency_type);
CREATE INDEX idx_house_members_house_status ON house_members (house_id, status);
