-- 카탈로그 준비 전 달성이나 완료 취소 뒤에도 레벨 보상 자격을 유지함.
ALTER TABLE personal_rooms ADD COLUMN highest_growth_level INT NOT NULL DEFAULT 0;
UPDATE personal_rooms SET highest_growth_level = growth_level;
ALTER TABLE personal_rooms ADD CONSTRAINT ck_room_highest_growth_level CHECK (highest_growth_level >= 0);
