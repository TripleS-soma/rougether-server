-- V66 전후에 실행하는 읽기 전용 진단임. 운영 데이터는 변경하지 않음.
-- 신규 목록 검증은 GET /api/v1/gacha?catalog=category, 기존 목록 검증은 catalog 생략임.
-- 1. 머신: 각 코드가 한 행이며 COIN 25/1회/테마 없음/기간 없음인지 확인함.
-- V66 직후 3개 모두 비활성, 별도 guarded activation 후 3개 모두 활성이어야 함.
SELECT code, id, name, cost_currency_type, cost_amount, draw_count,
       theme_id, starts_at, ends_at, is_active
FROM gacha
WHERE code IN ('wallpaper_gacha', 'floor_gacha', 'furniture_gacha')
ORDER BY FIELD(code, 'wallpaper_gacha', 'floor_gacha', 'furniture_gacha'), id;

-- 기존 앱이 보는 머신의 ID/활성 상태를 이행 전후 대조함. V66은 기존 머신을 중지하지 않음.
SELECT id, code, theme_id, starts_at, ends_at, is_active
FROM gacha
WHERE code NOT IN ('wallpaper_gacha', 'floor_gacha', 'furniture_gacha')
ORDER BY id;

-- 2. 전체 활성 카탈로그 대비 준비된 풀의 유효 distinct 아이템 수와 차이를 집계함.
-- 머신 is_active와 무관하게 준비된 등록을 셈. 실제 공개 여부는 위 1번과 함께 확인함.
-- gap은 오류 확정값이 아님: 기존 미등록 상품과 운영자가 중지한 풀도 포함됨.
WITH categories AS (
    SELECT 'wallpaper_gacha' AS code
    UNION ALL SELECT 'floor_gacha'
    UNION ALL SELECT 'furniture_gacha'
), eligible_items AS (
    SELECT i.id,
           CASE WHEN i.placement_type = 'surface_slot' AND i.surface_slot_type = 'wallpaper'
                    THEN 'wallpaper_gacha'
                WHEN i.placement_type = 'surface_slot' AND i.surface_slot_type = 'floor'
                    THEN 'floor_gacha'
                ELSE 'furniture_gacha' END AS code
    FROM items i JOIN themes t ON t.id = i.theme_id
    WHERE i.is_active = TRUE AND t.is_active = TRUE
      AND i.character_slot_type IS NULL
      AND i.category_code NOT IN ('character_accessory', 'background')
      AND ((i.placement_type = 'surface_slot' AND i.surface_slot_type IN ('wallpaper', 'floor'))
           OR (i.placement_type = 'positioned' AND i.surface_slot_type IS NULL))
), included_items AS (
    SELECT DISTINCT g.code, e.item_id
    FROM gacha g JOIN gacha_pool_entries e ON e.gacha_id = g.id
    WHERE g.code IN ('wallpaper_gacha', 'floor_gacha', 'furniture_gacha')
      AND e.is_active = TRUE AND e.reward_type = 'ITEM'
      AND (g.starts_at IS NULL OR g.starts_at <= CURRENT_TIMESTAMP)
      AND (g.ends_at IS NULL OR g.ends_at >= CURRENT_TIMESTAMP)
)
SELECT c.code, COUNT(DISTINCT eligible.id) AS eligible_catalog_count,
       COUNT(DISTINCT included.item_id) AS included_pool_count,
       COUNT(DISTINCT eligible.id) - COUNT(DISTINCT included.item_id) AS gap_count
FROM categories c
LEFT JOIN eligible_items eligible ON eligible.code = c.code
LEFT JOIN included_items included ON included.code = eligible.code AND included.item_id = eligible.id
GROUP BY c.code
ORDER BY FIELD(c.code, 'wallpaper_gacha', 'floor_gacha', 'furniture_gacha');

-- 3. 누락 아이템 상세: 미등록 상품과 기존 풀 이력이 있는 상품을 구분해 운영 검토함.
WITH eligible_items AS (
    SELECT i.id, i.name, i.asset_key, t.code AS theme_code,
           CASE WHEN i.placement_type = 'surface_slot' AND i.surface_slot_type = 'wallpaper'
                    THEN 'wallpaper_gacha'
                WHEN i.placement_type = 'surface_slot' AND i.surface_slot_type = 'floor'
                    THEN 'floor_gacha'
                ELSE 'furniture_gacha' END AS code
    FROM items i JOIN themes t ON t.id = i.theme_id
    WHERE i.is_active = TRUE AND t.is_active = TRUE
      AND i.character_slot_type IS NULL
      AND i.category_code NOT IN ('character_accessory', 'background')
      AND ((i.placement_type = 'surface_slot' AND i.surface_slot_type IN ('wallpaper', 'floor'))
           OR (i.placement_type = 'positioned' AND i.surface_slot_type IS NULL))
)
SELECT i.code, i.id AS item_id, i.name, i.theme_code, i.asset_key,
       CASE WHEN EXISTS (
           SELECT 1 FROM gacha_pool_entries history
           WHERE history.reward_type = 'ITEM' AND history.item_id = i.id
       ) THEN 'HAS_POOL_HISTORY_REVIEW_AVAILABILITY' ELSE 'NEVER_REGISTERED' END AS gap_reason
FROM eligible_items i
WHERE NOT EXISTS (
    SELECT 1 FROM gacha_pool_entries e JOIN gacha g ON g.id = e.gacha_id
    WHERE g.code = i.code AND e.item_id = i.id AND e.reward_type = 'ITEM'
      AND e.is_active = TRUE
      AND (g.starts_at IS NULL OR g.starts_at <= CURRENT_TIMESTAMP)
      AND (g.ends_at IS NULL OR g.ends_at >= CURRENT_TIMESTAMP)
)
ORDER BY i.code, i.theme_code, i.id;

-- 4. 같은 카테고리의 중복 활성 등록과 등급 분포를 확인함(중복 조회 결과는 0행이어야 함).
SELECT g.code, e.item_id, COUNT(*) AS duplicate_count
FROM gacha g JOIN gacha_pool_entries e ON e.gacha_id = g.id
WHERE g.code IN ('wallpaper_gacha', 'floor_gacha', 'furniture_gacha')
  AND e.reward_type = 'ITEM' AND e.is_active = TRUE
GROUP BY g.code, e.item_id
HAVING COUNT(*) > 1;

SELECT g.code, e.rarity, COUNT(DISTINCT e.item_id) AS active_item_count
FROM gacha g JOIN gacha_pool_entries e ON e.gacha_id = g.id
JOIN items i ON i.id = e.item_id JOIN themes t ON t.id = i.theme_id
WHERE g.code IN ('wallpaper_gacha', 'floor_gacha', 'furniture_gacha')
  AND e.reward_type = 'ITEM' AND e.is_active = TRUE AND i.is_active = TRUE AND t.is_active = TRUE
GROUP BY g.code, e.rarity
ORDER BY g.code, e.rarity;

-- 활성화 CALL에는 distinct가 아닌 활성 엔트리 행 수를 전달함. 중복/자격은 별도 guard도 확인함.
SELECT g.code, g.id, COUNT(e.id) AS registered_active_entry_count
FROM gacha g LEFT JOIN gacha_pool_entries e ON e.gacha_id = g.id AND e.is_active = TRUE
WHERE g.code IN ('wallpaper_gacha', 'floor_gacha', 'furniture_gacha')
GROUP BY g.code, g.id
ORDER BY FIELD(g.code, 'wallpaper_gacha', 'floor_gacha', 'furniture_gacha');

-- 5. 장식 머신에 잘못 등록한 활성 보상은 0행이어야 함(서비스는 이 보상을 별도로 차단함).
SELECT g.code, e.id AS entry_id, e.reward_type, e.item_id,
       i.category_code, i.placement_type, i.surface_slot_type, i.character_slot_type
FROM gacha g JOIN gacha_pool_entries e ON e.gacha_id = g.id
LEFT JOIN items i ON i.id = e.item_id
WHERE g.code IN ('wallpaper_gacha', 'floor_gacha', 'furniture_gacha')
  AND e.is_active = TRUE
  AND (e.reward_type <> 'ITEM' OR i.id IS NULL
       OR i.character_slot_type IS NOT NULL
       OR i.category_code IN ('character_accessory', 'background')
       OR NOT (
           (g.code = 'wallpaper_gacha' AND i.placement_type = 'surface_slot'
                AND COALESCE(i.surface_slot_type, '') = 'wallpaper')
           OR (g.code = 'floor_gacha' AND i.placement_type = 'surface_slot'
                AND COALESCE(i.surface_slot_type, '') = 'floor')
           OR (g.code = 'furniture_gacha' AND i.placement_type = 'positioned'
                AND i.surface_slot_type IS NULL)
       ));
