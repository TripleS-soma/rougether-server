-- 인벤토리가 파기돼도 가구 생성/정산 이력과 양쪽 회원의 구매 영수증은 보존한다.
ALTER TABLE furniture_generation_jobs
    ADD CONSTRAINT fk_furniture_job_inventory_nullable FOREIGN KEY (user_item_id)
        REFERENCES user_items(id) ON DELETE SET NULL;
ALTER TABLE furniture_generation_jobs DROP CONSTRAINT fk_furniture_job_inventory;

ALTER TABLE market_purchases MODIFY COLUMN user_item_id BIGINT NULL;
ALTER TABLE market_purchases
    ADD CONSTRAINT fk_market_purchases_item_nullable FOREIGN KEY (user_item_id)
        REFERENCES user_items(id) ON DELETE SET NULL;
ALTER TABLE market_purchases DROP CONSTRAINT fk_market_purchases_item;

-- 종료된 구 거래소 주문은 인벤토리 없이도 이력을 보존한다. 미체결 보유분 매도는 실물이 필요하다.
ALTER TABLE market_orders
    ADD CONSTRAINT ck_market_orders_inventory_shape CHECK (
        (side = 'BUY' AND source IS NULL AND user_item_id IS NULL AND quantity = 1)
        OR (side = 'SELL' AND source = 'INVENTORY' AND quantity = 1
            AND (status <> 'OPEN' OR user_item_id IS NOT NULL))
        OR (side = 'SELL' AND source = 'ISSUANCE' AND user_item_id IS NULL)
    );
ALTER TABLE market_orders DROP CONSTRAINT ck_market_orders_shape;
