-- 출석 전용 캐릭터는 온보딩 무료 선택과 뽑기에서 제외함.
ALTER TABLE characters ADD COLUMN is_attendance_reward BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE attendance_events ADD COLUMN reward_character_id BIGINT NULL;
ALTER TABLE attendance_events ADD COLUMN character_reward_day INT NOT NULL DEFAULT 0;
ALTER TABLE attendance_events ADD CONSTRAINT fk_attendance_reward_character
    FOREIGN KEY (reward_character_id) REFERENCES characters (id);
ALTER TABLE attendance_events ADD CONSTRAINT ck_attendance_character_reward CHECK (
    (reward_character_id IS NULL AND character_reward_day = 0)
    OR (reward_character_id IS NOT NULL AND mode = 'CUMULATIVE' AND character_reward_day BETWEEN 1 AND target_days)
);

-- 원화 업로드와 앱 연동 검증 후 관리자 카탈로그에서 활성화함. 이 migration은 지급하지 않음.
INSERT INTO characters (code, name, name_translations, base_asset_key, sort_order, is_active, is_attendance_reward)
SELECT 'fox', '여우', JSON_OBJECT('en', 'Fox'), 'characters/fox/base-v1.webp', 90, FALSE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM characters WHERE code = 'fox');

UPDATE characters SET is_attendance_reward = TRUE WHERE code = 'fox';

-- 기존 기간, 코인 보상, 최종 30회 완료 보상은 보존함.
UPDATE attendance_events
SET reward_character_id = (SELECT id FROM characters WHERE code = 'fox'), character_reward_day = 3
WHERE code = 'christmas_2026' AND mode = 'CUMULATIVE';

-- 신규 앱은 이 3포즈를 순서대로 사용함. 기존 포즈는 덮어쓰지 않음.
INSERT INTO character_poses (character_id, code, asset_key, sort_order, is_active, created_at, updated_at)
SELECT c.id, p.code, p.asset_key, p.sort_order, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM (
    SELECT 'rest' AS code, 'characters/fox/animations/idle.webp' AS asset_key, 10 AS sort_order
    UNION ALL SELECT 'seated', 'characters/fox/animations/pose-cycle.webp', 20
    UNION ALL SELECT 'tail-rest', 'characters/fox/animations/wave.webp', 30
) p JOIN characters c ON c.code = 'fox'
WHERE NOT EXISTS (SELECT 1 FROM character_poses cp WHERE cp.character_id = c.id AND cp.code = p.code);
