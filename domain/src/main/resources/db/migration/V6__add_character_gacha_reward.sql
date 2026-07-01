-- 캐릭터 뽑기 지원: 테마 무관 머신 허용 + 뽑기 보상에 캐릭터 추가.
-- gacha 는 원래 테마별(가구)만 가정해 theme_id 가 NOT NULL 이었으나, 캐릭터 뽑기는 테마 무관.
ALTER TABLE gacha MODIFY theme_id BIGINT NULL;

-- reward_type='CHARACTER' 일 때 지급할 캐릭터. 기존 ITEM/CURRENCY 보상과 배타적으로 사용.
ALTER TABLE gacha_pool_entries ADD COLUMN character_id BIGINT NULL;
ALTER TABLE gacha_pool_entries
    ADD CONSTRAINT fk_gpe_character FOREIGN KEY (character_id) REFERENCES characters (id);
