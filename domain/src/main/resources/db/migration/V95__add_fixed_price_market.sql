-- 기존 주문과 거래 이력은 보존함. 제작자가 가격을 정하기 전에는 판매하지 않음.
ALTER TABLE market_assets ADD COLUMN sale_price INT NULL;
ALTER TABLE market_assets ADD COLUMN sale_active BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE market_assets ADD CONSTRAINT ck_market_assets_sale_price
    CHECK (sale_price IS NULL OR sale_price BETWEEN 1 AND 1000);

CREATE TABLE market_purchases (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL,
    buyer_user_id BIGINT NOT NULL,
    seller_user_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    price INT NOT NULL,
    fee_amount INT NOT NULL,
    user_item_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_market_purchases_request UNIQUE (buyer_user_id, request_id),
    CONSTRAINT uk_market_purchases_buyer_asset UNIQUE (buyer_user_id, asset_id),
    CONSTRAINT fk_market_purchases_asset FOREIGN KEY (asset_id) REFERENCES market_assets(id),
    CONSTRAINT fk_market_purchases_buyer FOREIGN KEY (buyer_user_id) REFERENCES users(id),
    CONSTRAINT fk_market_purchases_seller FOREIGN KEY (seller_user_id) REFERENCES users(id),
    CONSTRAINT fk_market_purchases_item FOREIGN KEY (user_item_id) REFERENCES user_items(id),
    CONSTRAINT ck_market_purchases_price CHECK (price BETWEEN 1 AND 1000 AND fee_amount >= 0 AND fee_amount <= price)
);
