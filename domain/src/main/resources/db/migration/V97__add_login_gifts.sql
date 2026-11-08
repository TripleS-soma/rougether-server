CREATE TABLE login_gift_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(50) NOT NULL,
    title VARCHAR(120) NOT NULL,
    starts_at TIMESTAMP(6) NOT NULL,
    ends_at TIMESTAMP(6) NOT NULL,
    coin_amount INT NOT NULL,
    reward_item_id BIGINT NOT NULL,
    is_enabled BOOLEAN NOT NULL,
    CONSTRAINT uq_login_gift_code UNIQUE (code),
    CONSTRAINT ck_login_gift_period CHECK (starts_at < ends_at),
    CONSTRAINT ck_login_gift_coins CHECK (coin_amount BETWEEN 0 AND 1000000),
    CONSTRAINT fk_login_gift_item FOREIGN KEY (reward_item_id) REFERENCES items (id)
);

CREATE TABLE login_gift_participations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    eligible_at TIMESTAMP(6) NOT NULL,
    granted_at TIMESTAMP(6) NULL,
    acknowledged_at TIMESTAMP(6) NULL,
    reward_user_item_id BIGINT NULL,
    CONSTRAINT uq_login_gift_user UNIQUE (event_id, user_id),
    CONSTRAINT fk_login_gift_participation_event FOREIGN KEY (event_id) REFERENCES login_gift_events (id),
    CONSTRAINT fk_login_gift_participation_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_login_gift_participation_item FOREIGN KEY (reward_user_item_id) REFERENCES user_items (id),
    CONSTRAINT ck_login_gift_receipt CHECK (
        (granted_at IS NULL AND reward_user_item_id IS NULL AND acknowledged_at IS NULL)
        OR (granted_at IS NOT NULL AND reward_user_item_id IS NOT NULL)
    )
);
CREATE INDEX idx_login_gift_pending ON login_gift_participations (granted_at, id);
CREATE INDEX idx_login_gift_user_receipts ON login_gift_participations (user_id, id);

-- 테마는 상점에서 숨기되 보유 가구는 정상 배치 가능함. 가격과 뽑기 풀도 등록하지 않음.
INSERT INTO themes (code, name, is_active)
VALUES ('pepero_day_20261111', '빼빼로데이 기념 선물', FALSE);

INSERT INTO items (theme_id, category_code, placement_type, name,
                   asset_key, is_limited, is_active, default_slot, default_scale)
SELECT id, 'furniture', 'positioned', '11:11 소원 관측기',
       'items/pepero-day-20261111/furniture/wish-observatory-v1.png', TRUE, TRUE, 'midRight', 1.00
FROM themes WHERE code = 'pepero_day_20261111';

-- UTC 저장: 2026-11-11 00:00 KST 이상, 2026-11-12 00:00 KST 미만임.
-- MySQL 실행 주석을 사용해 H2 로컬 환경에서는 세션 설정을 건너뜀.
/*!40103 SET @login_gift_previous_time_zone = @@session.time_zone */;
/*!40103 SET SESSION time_zone = '+00:00' */;
INSERT INTO login_gift_events (code, title, starts_at, ends_at, coin_amount, reward_item_id, is_enabled)
SELECT 'PEPERO_DAY_20261111', '빼빼로데이 접속 선물',
       '2026-11-10 15:00:00', '2026-11-11 15:00:00', 111, id, TRUE
FROM items WHERE asset_key = 'items/pepero-day-20261111/furniture/wish-observatory-v1.png';
/*!40103 SET SESSION time_zone = @login_gift_previous_time_zone */;
