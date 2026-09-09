-- 완료 보상에 따른 개인 방 성장. 기존 이력 소급은 새 서버 전환 후 별도 도구로 실행함.
ALTER TABLE personal_rooms ADD COLUMN growth_points BIGINT NOT NULL DEFAULT 0;
-- 기존에 설정된 레벨이 있다면 그 레벨의 최소 포인트를 보존함.
-- 각 구간은 20, 22, 24, ... 이므로 레벨 L의 최소 누적치는 L * (L + 19)임.
UPDATE personal_rooms SET growth_points = CAST(growth_level AS SIGNED) * (growth_level + 19);
ALTER TABLE personal_rooms ADD CONSTRAINT ck_personal_room_growth_points CHECK (growth_points >= 0);

-- 실제 성장 지급액을 별도로 남김. 과거 완료도 소급 도구가 이 표식을 함께 반영한 뒤에 회수 대상이 됨.
ALTER TABLE routine_logs ADD COLUMN growth_reward_amount INT NOT NULL DEFAULT 0;
ALTER TABLE todos ADD COLUMN growth_reward_amount INT NOT NULL DEFAULT 0;
