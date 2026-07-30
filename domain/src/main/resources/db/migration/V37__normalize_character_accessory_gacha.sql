-- 캐릭터 악세사리는 상점 직접 구매 대상이 아니며 별도 등급 없이 동일 확률로 뽑는다.
UPDATE items
SET purchase_currency_type = NULL,
    price_amount = NULL
WHERE placement_type = 'character';

UPDATE gacha_pool_entries
SET rarity = NULL,
    weight = 1
WHERE reward_type = 'ITEM'
  AND item_id IN (
      SELECT id
      FROM items
      WHERE placement_type = 'character'
  );
