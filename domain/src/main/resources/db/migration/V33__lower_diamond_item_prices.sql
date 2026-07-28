-- 다이아 판매 물품 가격을 기존의 1/10로 조정한다.
-- 현재 dev 카탈로그의 다이아 판매 물품은 모두 100으로, 적용 후 10이 된다.
-- 구매 불가인 뽑기 전용 물품(price_amount IS NULL)과 다른 재화 물품은 변경하지 않는다.
UPDATE items
SET price_amount = price_amount / 10
WHERE purchase_currency_type = 'DIAMOND'
  AND price_amount IS NOT NULL;
