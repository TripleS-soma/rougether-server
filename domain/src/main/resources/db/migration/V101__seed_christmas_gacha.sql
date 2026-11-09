-- 기존 TIMESTAMP(0)에 정확한 종료 경계를 저장함. ChristmasEventPolicy가 KST 12/26부터 거절함.
-- 운영 공개는 전체 서버/앱 배포 검증 뒤 별도 활성화함. 기존 머신과 일반 풀은 변경하지 않음.
INSERT INTO gacha (code, name, cost_currency_type, cost_amount, draw_count, starts_at, ends_at,
                   is_active, theme_id, created_at, updated_at)
SELECT 'christmas_2026', '크리스마스', 'COIN', 25, 1,
       '2026-11-08 15:00:00.000000', '2026-12-25 15:00:00', FALSE,
       (SELECT id FROM themes WHERE code = 'snowy_christmas_202611'), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM gacha WHERE code = 'christmas_2026');

-- 동일 환경에서 등록된 asset key와 theme code로만 찾음. 환경별 ID를 하드코딩하지 않음.
INSERT INTO gacha_pool_entries (gacha_id, reward_type, item_id, rarity, weight, is_active)
SELECT g.id, 'ITEM', i.id,
       CASE
           WHEN i.asset_key IN (
               'items/snowy-christmas-202611/furniture/christmas-tree-animated-v1.webp',
               'items/snowy-christmas-202611/furniture/stocking-fireplace-animated-v1.webp') THEN '전설'
           WHEN i.asset_key IN (
               'items/snowy-christmas-202611/furniture/plaid-sofa-v1.png',
               'items/snowy-christmas-202611/furniture/cocoa-table-animated-v1.webp',
               'items/snowy-christmas-202611/furniture/christmas-wreath-v1.png') THEN '희귀'
           ELSE '일반'
       END, 1, TRUE
FROM gacha g
JOIN themes t ON t.code = 'snowy_christmas_202611'
JOIN items i ON i.theme_id = t.id
WHERE g.code = 'christmas_2026'
  AND i.asset_key IN (
      'items/snowy-christmas-202611/furniture/christmas-tree-animated-v1.webp',
      'items/snowy-christmas-202611/furniture/stocking-fireplace-animated-v1.webp',
      'items/snowy-christmas-202611/furniture/plaid-sofa-v1.png',
      'items/snowy-christmas-202611/furniture/gift-stack-v1.png',
      'items/snowy-christmas-202611/furniture/cocoa-table-animated-v1.webp',
      'items/snowy-christmas-202611/furniture/snowy-window-animated-v1.webp',
      'items/snowy-christmas-202611/furniture/christmas-wreath-v1.png',
      'items/snowy-christmas-202611/furniture/winter-rug-v1.png',
      'items/snowy-christmas-202611/wallpaper/snowflake-wallpaper-panel-free-v2.png',
      'items/snowy-christmas-202611/floor/honey-winter-floor-v1.png')
  AND NOT EXISTS (SELECT 1 FROM gacha_pool_entries p WHERE p.gacha_id = g.id AND p.item_id = i.id);
