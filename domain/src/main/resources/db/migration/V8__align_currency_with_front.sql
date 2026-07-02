-- 재화 체계를 프론트(rougether-mobile)와 정렬 + 재화 정합 DB 방어선.
-- 코인 = 루틴 보상·뽑기, 다이아 = 상점 구매·중복 전환.

-- 아이템 구매 재화는 다이아(스펙·프론트 기준). 초기 카탈로그가 COIN 으로 적재돼 있어 보정.
UPDATE items SET purchase_currency_type = 'DIAMOND' WHERE purchase_currency_type = 'COIN';

-- 가구(테마) 뽑기 단가 250 (10연 1250 = 단가 x5, 앱 화면 기준). 캐릭터 뽑기(theme_id IS NULL)는 1000 유지.
UPDATE gacha SET cost_amount = 250 WHERE theme_id IS NOT NULL;

-- 재화 정합 방어선: 유저당 재화별 지갑은 하나 (동시 요청의 다이아 지갑 중복 발급 방지).
ALTER TABLE user_wallets
    ADD CONSTRAINT uq_user_wallets_user_currency UNIQUE (user_id, currency_type);

-- 같은 아이템 중복 보유 방지 (동시 구매/뽑기의 이중 지급 방지). 현재 user_items 삭제 흐름이 없어
-- deleted_at 무관 UNIQUE 로 충분 - 재획득 흐름이 생기면 이 제약을 재설계한다.
ALTER TABLE user_items
    ADD CONSTRAINT uq_user_items_user_item UNIQUE (user_id, item_id);
