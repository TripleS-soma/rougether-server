-- 격리된 MySQL에 제작자, 구매자, 활동 회원, 구 거래소 미체결 주문자를 한 명씩 만든다.
INSERT INTO users (id, created_at, updated_at, deleted_at) VALUES
    (9001, NOW(), NOW(), NOW() - INTERVAL 2 HOUR),
    (9002, NOW(), NOW(), NOW() - INTERVAL 2 HOUR),
    (9003, NOW(), NOW(), NULL),
    (9004, NOW(), NOW(), NOW() - INTERVAL 2 HOUR);
INSERT INTO categories (user_id, name, sort_order, created_at, updated_at)
SELECT id, '루틴', 0, NOW(), NOW() FROM users WHERE id BETWEEN 9001 AND 9004;

INSERT INTO items (id, theme_id, category_code, placement_type, name, asset_key, is_limited, is_active)
SELECT 9100, id, 'FURNITURE', 'FLOOR', '테스트 가구', 'item/purge.png', FALSE, TRUE
FROM themes WHERE code = 'photo_furniture';
INSERT INTO user_items (id, user_id, item_id, acquired_at) VALUES
    (9101, 9001, 9100, NOW()), (9102, 9002, 9100, NOW()),
    (9103, 9003, 9100, NOW()), (9104, 9004, 9100, NOW());
INSERT INTO furniture_generation_jobs
    (id, user_id, request_id, input_digest, target_hint, status, action, user_item_id,
     feedback, correction_prompt, next_run_at, expires_at, created_at, updated_at)
VALUES ('purge-job', 9001, 'request', 'digest', '', 'SUCCEEDED', 'REVIEW', 9101,
        '', '', NOW(), NOW(), NOW(), NOW());
INSERT INTO market_assets (id, item_id, creator_user_id, total_supply, unissued_quantity, status, created_at, updated_at)
VALUES (9200, 9100, 9001, 4, 0, 'ACTIVE', NOW(), NOW());
INSERT INTO market_purchases (asset_id, buyer_user_id, seller_user_id, request_id, price, fee_amount, user_item_id, created_at)
VALUES (9200, 9002, 9001, 'withdrawn', 100, 5, 9102, NOW()),
       (9200, 9003, 9001, 'active', 100, 5, 9103, NOW());

INSERT INTO market_commands (id, user_id, request_id, type, asset_id, side, source, price, quantity,
                             escrow_user_item_id, status, created_at)
VALUES (9300, 9004, 'legacy', 'PLACE', 9200, 'SELL', 'INVENTORY', 100, 1, 9104, 'APPLIED', NOW());
INSERT INTO market_orders (command_id, asset_id, user_id, side, source, user_item_id, price,
                           quantity, escrow_remaining, engine_seq, status, expires_at, created_at, updated_at)
VALUES (9300, 9200, 9004, 'SELL', 'INVENTORY', 9104, 100, 1, 1, 1, 'OPEN', NOW(), NOW(), NOW());
