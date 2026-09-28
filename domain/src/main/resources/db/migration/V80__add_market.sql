-- AI 가구 에디션 거래소(#406).
-- 보유는 user_items 로 표현하고, 판매 등록 시 deleted_at 을 채워 맡겨 둔다(취소·만료 시 null 복귀).
-- 매칭 엔진 하나가 market_commands 를 engine_seq 순서로 처리하며, market_engine_lease 로 단일 담당을 보장한다.

-- 1) user_items 유니크 재설계: 활성 row 만 사람당 아이템 1개, 비활성 이력은 여러 개 허용.
--    기존 유니크는 user_id FK 가 인덱스로 쓰고 있어 새 유니크를 먼저 만들고 제거한다(V23 과 같은 순서).
ALTER TABLE user_items
    ADD COLUMN active_flag TINYINT GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN 1 ELSE NULL END);

ALTER TABLE user_items
    ADD CONSTRAINT uq_user_items_active UNIQUE (user_id, item_id, active_flag);

ALTER TABLE user_items
    DROP INDEX uq_user_items_user_item;

-- 2) 상장 종목. AI 가구(items row) 1개당 1회.
CREATE TABLE market_assets (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    item_id           BIGINT       NOT NULL,
    creator_user_id   BIGINT       NULL,
    total_supply      INT          NOT NULL,
    unissued_quantity INT          NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    created_at        TIMESTAMP(6) NOT NULL,
    updated_at        TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_market_assets_item UNIQUE (item_id),
    CONSTRAINT fk_market_assets_item FOREIGN KEY (item_id) REFERENCES items (id),
    CONSTRAINT fk_market_assets_creator FOREIGN KEY (creator_user_id) REFERENCES users (id),
    CONSTRAINT ck_market_assets_supply CHECK (total_supply BETWEEN 1 AND 10),
    CONSTRAINT ck_market_assets_unissued CHECK (unissued_quantity BETWEEN 0 AND total_supply),
    CONSTRAINT ck_market_assets_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);
CREATE INDEX idx_market_assets_status ON market_assets (status, id);

-- 3) 접수 대장. API 가 에스크로와 같은 트랜잭션에서 PENDING 으로 넣고 엔진이 engine_seq 를 부여한다.
--    target_order_id·order_id 는 market_orders 와의 순환 FK 를 피하려고 논리 참조로 둔다.
CREATE TABLE market_commands (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    user_id             BIGINT       NOT NULL,
    request_id          VARCHAR(64)  NOT NULL,
    type                VARCHAR(10)  NOT NULL,
    asset_id            BIGINT       NOT NULL,
    side                VARCHAR(4)   NULL,
    source              VARCHAR(10)  NULL,
    price               INT          NULL,
    quantity            INT          NULL,
    escrow_amount       INT          NULL,
    escrow_user_item_id BIGINT       NULL,
    target_order_id     BIGINT       NULL,
    engine_seq          BIGINT       NULL,
    status              VARCHAR(10)  NOT NULL,
    reject_code         VARCHAR(50)  NULL,
    order_id            BIGINT       NULL,
    attempts            INT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMP(6) NOT NULL,
    applied_at          TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_market_commands_request UNIQUE (user_id, request_id),
    CONSTRAINT uk_market_commands_seq UNIQUE (engine_seq),
    CONSTRAINT fk_market_commands_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_market_commands_asset FOREIGN KEY (asset_id) REFERENCES market_assets (id),
    CONSTRAINT fk_market_commands_user_item FOREIGN KEY (escrow_user_item_id) REFERENCES user_items (id),
    CONSTRAINT ck_market_commands_type CHECK (type IN ('PLACE', 'CANCEL', 'EXPIRE')),
    CONSTRAINT ck_market_commands_side CHECK (side IS NULL OR side IN ('BUY', 'SELL')),
    CONSTRAINT ck_market_commands_source CHECK (source IS NULL OR source IN ('INVENTORY', 'ISSUANCE')),
    CONSTRAINT ck_market_commands_status CHECK (status IN ('PENDING', 'APPLIED', 'REJECTED')),
    CONSTRAINT ck_market_commands_values CHECK ((price IS NULL OR price BETWEEN 1 AND 1000)
        AND (quantity IS NULL OR quantity >= 1) AND (escrow_amount IS NULL OR escrow_amount >= 0) AND attempts >= 0)
);
CREATE INDEX idx_market_commands_pending ON market_commands (status, id);

-- 4) 주문(호가창 정본).
CREATE TABLE market_orders (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    command_id       BIGINT       NOT NULL,
    asset_id         BIGINT       NOT NULL,
    user_id          BIGINT       NOT NULL,
    side             VARCHAR(4)   NOT NULL,
    source           VARCHAR(10)  NULL,
    user_item_id     BIGINT       NULL,
    price            INT          NOT NULL,
    quantity         INT          NOT NULL,
    filled_quantity  INT          NOT NULL DEFAULT 0,
    escrow_remaining INT          NOT NULL,
    engine_seq       BIGINT       NOT NULL,
    status           VARCHAR(10)  NOT NULL,
    expires_at       TIMESTAMP(6) NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL,
    updated_at       TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_market_orders_command UNIQUE (command_id),
    CONSTRAINT fk_market_orders_command FOREIGN KEY (command_id) REFERENCES market_commands (id),
    CONSTRAINT fk_market_orders_asset FOREIGN KEY (asset_id) REFERENCES market_assets (id),
    CONSTRAINT fk_market_orders_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_market_orders_user_item FOREIGN KEY (user_item_id) REFERENCES user_items (id),
    CONSTRAINT ck_market_orders_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_market_orders_source CHECK (source IS NULL OR source IN ('INVENTORY', 'ISSUANCE')),
    CONSTRAINT ck_market_orders_price CHECK (price BETWEEN 1 AND 1000),
    CONSTRAINT ck_market_orders_quantity CHECK (quantity >= 1),
    CONSTRAINT ck_market_orders_filled CHECK (filled_quantity BETWEEN 0 AND quantity),
    CONSTRAINT ck_market_orders_escrow CHECK (escrow_remaining >= 0),
    CONSTRAINT ck_market_orders_status CHECK (status IN ('OPEN', 'FILLED', 'CANCELLED', 'EXPIRED')),
    -- 매수·보유분 매도는 수량 1, 보유분 매도만 맡긴 user_item 을 가리킴, 발행 재고 매도만 여러 개 가능
    CONSTRAINT ck_market_orders_shape CHECK (
        (side = 'BUY' AND source IS NULL AND user_item_id IS NULL AND quantity = 1)
        OR (side = 'SELL' AND source = 'INVENTORY' AND user_item_id IS NOT NULL AND quantity = 1)
        OR (side = 'SELL' AND source = 'ISSUANCE' AND user_item_id IS NULL))
);
CREATE INDEX idx_market_orders_book ON market_orders (asset_id, side, status, price, engine_seq);
CREATE INDEX idx_market_orders_user ON market_orders (user_id, status, id);
CREATE INDEX idx_market_orders_expiry ON market_orders (status, expires_at);

-- 5) 체결 내역. fee_amount 는 소각된 수수료.
CREATE TABLE market_trades (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    asset_id        BIGINT       NOT NULL,
    engine_seq      BIGINT       NOT NULL,
    buy_order_id    BIGINT       NOT NULL,
    sell_order_id   BIGINT       NOT NULL,
    buyer_user_id   BIGINT       NOT NULL,
    seller_user_id  BIGINT       NOT NULL,
    royalty_user_id BIGINT       NULL,
    price           INT          NOT NULL,
    quantity        INT          NOT NULL,
    royalty_amount  INT          NOT NULL,
    fee_amount      INT          NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_market_trades_asset FOREIGN KEY (asset_id) REFERENCES market_assets (id),
    CONSTRAINT fk_market_trades_buy FOREIGN KEY (buy_order_id) REFERENCES market_orders (id),
    CONSTRAINT fk_market_trades_sell FOREIGN KEY (sell_order_id) REFERENCES market_orders (id),
    CONSTRAINT fk_market_trades_buyer FOREIGN KEY (buyer_user_id) REFERENCES users (id),
    CONSTRAINT fk_market_trades_seller FOREIGN KEY (seller_user_id) REFERENCES users (id),
    CONSTRAINT fk_market_trades_royalty FOREIGN KEY (royalty_user_id) REFERENCES users (id),
    CONSTRAINT ck_market_trades_amounts CHECK (price >= 1 AND quantity >= 1 AND royalty_amount >= 0 AND fee_amount >= 0
        AND royalty_amount + fee_amount <= price * quantity),
    -- 엔진 재시도로 같은 체결이 두 번 정산되는 것을 막는 최후 방어선
    CONSTRAINT uk_market_trades_fill UNIQUE (engine_seq, buy_order_id, sell_order_id)
);
CREATE INDEX idx_market_trades_asset ON market_trades (asset_id, id);
CREATE INDEX idx_market_trades_seq ON market_trades (engine_seq);

-- 6) 엔진 담당 리스(단일 행). 담당이 바뀔 때마다 fencing_token 을 1 올린다.
CREATE TABLE market_engine_lease (
    id              TINYINT      NOT NULL,
    owner_token     VARCHAR(36)  NULL,
    fencing_token   BIGINT       NOT NULL,
    lease_until     TIMESTAMP(6) NULL,
    last_engine_seq BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT ck_market_engine_lease_single CHECK (id = 1)
);
INSERT INTO market_engine_lease (id, owner_token, fencing_token, lease_until, last_engine_seq)
VALUES (1, NULL, 0, NULL, 0);
