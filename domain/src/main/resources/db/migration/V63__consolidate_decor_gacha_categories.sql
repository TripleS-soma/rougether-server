-- 테마별 인테리어 뽑기를 테마 공통 벽지·바닥·가구 3종으로 통합함.
-- 기존 머신/풀/보유 이력의 ID를 보존하고, 현재 배출 가능한 상시 소스의 등록만 복사함.
-- 비활성 아이템·테마의 등록은 유지함. 실제 배출 시 기존 활성 필터가 적용되므로
-- 운영자가 아이템·테마를 다시 활성화하면 통합 풀에서도 동일하게 복원됨.
-- 비활성 풀·머신, 시작 전 머신, 종료 시각이 있는 모든 한정 머신의 전용 보상은 편입하지 않음.
-- 진행 중인 한정 머신도 제외하여 원래 종료 이후에 상시 풀로 계속 배출되는 것을 막음.
CREATE TEMPORARY TABLE gacha_category_entries_v63 AS
SELECT g.id AS source_gacha_id,
       CASE
           WHEN i.placement_type = 'surface_slot' AND i.surface_slot_type = 'wallpaper'
               THEN 'wallpaper_gacha'
           WHEN i.placement_type = 'surface_slot' AND i.surface_slot_type = 'floor'
               THEN 'floor_gacha'
           ELSE 'furniture_gacha'
       END AS target_code,
       i.id AS item_id,
       CASE e.rarity WHEN '전설' THEN 3 WHEN '희귀' THEN 2 ELSE 1 END AS rarity_rank
FROM gacha_pool_entries e
JOIN gacha g ON g.id = e.gacha_id
JOIN items i ON i.id = e.item_id
WHERE g.is_active = TRUE
  AND (g.starts_at IS NULL OR g.starts_at <= CURRENT_TIMESTAMP)
  AND g.ends_at IS NULL
  AND g.code NOT IN ('wallpaper_gacha', 'floor_gacha', 'furniture_gacha')
  AND RIGHT(g.code, 12) <> '_accessories'
  AND e.is_active = TRUE
  AND e.reward_type = 'ITEM'
  AND i.character_slot_type IS NULL
  AND i.category_code NOT IN ('character_accessory', 'background')
  AND (
      (i.placement_type = 'surface_slot' AND i.surface_slot_type IN ('wallpaper', 'floor'))
      OR (i.placement_type = 'positioned' AND i.surface_slot_type IS NULL)
  );

INSERT INTO gacha (
    code, name, cost_currency_type, cost_amount, draw_count,
    starts_at, ends_at, is_active, created_at, updated_at, theme_id
)
SELECT category.code, category.name, 'COIN', 25, 1,
       NULL, NULL, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL
FROM (
    SELECT 'wallpaper_gacha' AS code, '벽지 뽑기' AS name
    UNION ALL SELECT 'floor_gacha', '바닥 뽑기'
    UNION ALL SELECT 'furniture_gacha', '가구 뽑기'
) category
WHERE NOT EXISTS (SELECT 1 FROM gacha existing WHERE existing.code = category.code);

-- 사전 등록된 정식 코드가 있더라도 동일한 비용·기간·테마 계약으로 맞춤.
UPDATE gacha
SET name = CASE code
        WHEN 'wallpaper_gacha' THEN '벽지 뽑기'
        WHEN 'floor_gacha' THEN '바닥 뽑기'
        ELSE '가구 뽑기'
    END,
    cost_currency_type = 'COIN', cost_amount = 25, draw_count = 1,
    starts_at = NULL, ends_at = NULL, theme_id = NULL,
    is_active = TRUE, updated_at = CURRENT_TIMESTAMP
WHERE code IN ('wallpaper_gacha', 'floor_gacha', 'furniture_gacha');

-- 같은 아이템이 여러 테마 풀에 중복돼도 통합 풀에는 한 번만 등록함.
-- 등급이 충돌하면 전설 > 희귀 > 일반 중 최상위 지원 등급을 보존함.
-- 지원 등급이 전혀 없거나 누락된 과거 등록은 일반로 정규화함.
-- 등급별 확률(70/25/5)은 서비스에서 계산하므로 개별 weight는 1로 고정함.
INSERT INTO gacha_pool_entries (
    gacha_id, reward_type, item_id, character_id,
    currency_type, reward_amount, rarity, weight, is_active
)
SELECT target.id, 'ITEM', source.item_id, NULL, NULL, NULL,
       CASE MAX(source.rarity_rank) WHEN 3 THEN '전설' WHEN 2 THEN '희귀' ELSE '일반' END,
       1, TRUE
FROM gacha_category_entries_v63 source
JOIN gacha target ON target.code = source.target_code
WHERE NOT EXISTS (
    SELECT 1 FROM gacha_pool_entries existing
    WHERE existing.gacha_id = target.id
      AND existing.reward_type = 'ITEM'
      AND existing.item_id = source.item_id
)
GROUP BY target.id, source.item_id;

-- 실제 등록을 이관한 테마 머신만 내려 이력과 예외 머신을 보존함.
-- 캐릭터/악세사리 보상이 섞인 머신은 별도 운영 범위이므로 그대로 유지함.
UPDATE gacha
SET is_active = FALSE, updated_at = CURRENT_TIMESTAMP
WHERE theme_id IS NOT NULL
  AND id IN (SELECT source_gacha_id FROM gacha_category_entries_v63)
  AND NOT EXISTS (
      SELECT 1 FROM gacha_pool_entries special
      LEFT JOIN items accessory ON accessory.id = special.item_id
      WHERE special.gacha_id = gacha.id
        AND (
            special.reward_type = 'CHARACTER'
            OR (special.reward_type = 'ITEM' AND (
                accessory.placement_type = 'character'
                OR accessory.category_code = 'character_accessory'
                OR accessory.character_slot_type IS NOT NULL
            ))
        )
  );

DROP TABLE gacha_category_entries_v63;
