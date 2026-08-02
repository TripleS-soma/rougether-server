-- 가구(테마) 뽑기 단가 250 -> 25 (10연 125 = 단가 x5).
-- 배경: 코인 획득 경로가 루틴 완료(10)·투두 완료(5)뿐이고 일일 보상 캡이 4건이라 하루 최대 40코인이다.
-- 단가 250 은 단챠 1회에 6~7일이 걸려 뽑기 체감이 사실상 막혀 있었다(멘토링 피드백: "완료까지 오래걸림").
-- 캐릭터 뽑기(theme_id IS NULL)는 1000 유지 — 이번 조정 범위 밖.
-- admin 의 신규 테마 자동 머신 생성 기본값도 ItemSlotService.ITEM_GACHA_COST_COIN 에서 함께 25 로 맞춘다.
UPDATE gacha SET cost_amount = 25 WHERE theme_id IS NOT NULL;
