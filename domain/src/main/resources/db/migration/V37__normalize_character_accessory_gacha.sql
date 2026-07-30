-- 캐릭터 악세사리는 상점 직접 구매 대상이 아니며 별도 등급 없이 전용 머신에서 동일 확률로 뽑는다.
UPDATE items
SET purchase_currency_type = NULL,
    price_amount = NULL
WHERE placement_type = 'character';

-- 기존 카탈로그의 활성 악세사리도 재적재 없이 배출되도록 테마별 전용 머신을 보장한다.
-- gacha.code 최대 길이 50: theme code 앞 38자 + "_accessories"(12자).
INSERT INTO gacha (
    code, name, cost_currency_type, cost_amount, draw_count,
    starts_at, ends_at, is_active, created_at, updated_at, theme_id
)
SELECT CONCAT(SUBSTRING(t.code, 1, 38), '_accessories'),
       CONCAT(t.name, ' 악세사리 뽑기'),
       'COIN', 25, 1,
       NULL, NULL, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, t.id
FROM themes t
WHERE t.is_active = TRUE
  AND EXISTS (
      SELECT 1
      FROM items i
      WHERE i.theme_id = t.id
        AND i.placement_type = 'character'
        AND i.is_active = TRUE
  )
  AND NOT EXISTS (
      SELECT 1
      FROM gacha g
      WHERE g.theme_id = t.id
        AND g.code = CONCAT(SUBSTRING(t.code, 1, 38), '_accessories')
        AND g.is_active = TRUE
  );

-- 과거 로직이 가구 머신에 섞어 둔 악세사리 엔트리는 배출만 중단하고 이력은 보존한다.
UPDATE gacha_pool_entries
SET is_active = FALSE
WHERE reward_type = 'ITEM'
  AND item_id IN (
      SELECT id
      FROM items
      WHERE placement_type = 'character'
  )
  AND gacha_id NOT IN (
      SELECT g.id
      FROM gacha g
      JOIN themes t ON t.id = g.theme_id
      WHERE g.code = CONCAT(SUBSTRING(t.code, 1, 38), '_accessories')
        AND g.is_active = TRUE
  );

UPDATE gacha_pool_entries
SET rarity = NULL,
    weight = 1
WHERE reward_type = 'ITEM'
  AND item_id IN (
      SELECT id
      FROM items
      WHERE placement_type = 'character'
  );

-- 이미 items 에 있지만 어느 활성 풀에도 없던 악세사리까지 전용 머신에 멱등 등록한다.
INSERT INTO gacha_pool_entries (
    gacha_id, reward_type, item_id, character_id,
    currency_type, reward_amount, rarity, weight, is_active
)
SELECT g.id, 'ITEM', i.id, NULL,
       NULL, NULL, NULL, 1, TRUE
FROM items i
JOIN themes t ON t.id = i.theme_id
JOIN gacha g
  ON g.theme_id = t.id
 AND g.code = CONCAT(SUBSTRING(t.code, 1, 38), '_accessories')
 AND g.is_active = TRUE
WHERE i.placement_type = 'character'
  AND i.is_active = TRUE
  AND t.is_active = TRUE
  AND NOT EXISTS (
      SELECT 1
      FROM gacha_pool_entries e
      WHERE e.gacha_id = g.id
        AND e.reward_type = 'ITEM'
        AND e.item_id = i.id
        AND e.is_active = TRUE
  );
