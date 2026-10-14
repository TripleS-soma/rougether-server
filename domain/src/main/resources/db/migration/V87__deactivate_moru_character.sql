-- 모루 레벨 5 보상 제거(제품 결정 2026-10-14). 모루 카탈로그를 비활성화하고 착용자를 고양이로 되돌림.
-- 모루 보유 행은 지우지 않음(비활성 캐릭터는 보유 목록·착용에서 제외되고, 온보딩 무료 선택권 판정에 이력이 쓰임).
-- 다시 실행해도 안전함: 두 번째 실행에서는 모루 착용자가 없어 착용 변경 단계가 모두 아무 행도 바꾸지 않음.
-- 활성 고양이 카탈로그가 없으면 착용은 그대로 두고 비활성화만 함(착용 캐릭터가 사라지는 것을 막음).
-- MySQL 은 UPDATE/INSERT 대상 테이블을 같은 문의 하위 조회에서 다시 읽는 것을 제한하므로(1093) 대상을 임시 테이블에 고정함.

CREATE TEMPORARY TABLE moru_wearers_v87 AS
SELECT uc.user_id AS user_id,
       uc.id AS moru_user_character_id,
       (SELECT MIN(cat.id) FROM characters cat WHERE cat.code = 'cat' AND cat.is_active = TRUE) AS cat_character_id
FROM user_characters uc
JOIN characters moru ON moru.id = uc.character_id
WHERE moru.code = 'moru'
  AND uc.is_selected = TRUE
  AND uc.deleted_at IS NULL;

DELETE FROM moru_wearers_v87 WHERE cat_character_id IS NULL;

-- 고양이를 보유하지 않은 착용자에게 고양이를 지급함. 착용은 아래 단계에서 보유 여부와 관계없이 일괄 반영함.
INSERT INTO user_characters (user_id, character_id, is_selected, acquired_at, created_at, updated_at)
SELECT w.user_id, w.cat_character_id, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM moru_wearers_v87 w
WHERE NOT EXISTS (
    SELECT 1 FROM user_characters owned
    WHERE owned.user_id = w.user_id
      AND owned.character_id = w.cat_character_id
      AND owned.deleted_at IS NULL
);

UPDATE user_characters
SET is_selected = FALSE, updated_at = CURRENT_TIMESTAMP
WHERE id IN (SELECT moru_user_character_id FROM moru_wearers_v87);

-- 고양이 보유 행이 여러 개인 과거 데이터도 착용은 하나만 되도록 사용자별 가장 오래된 행을 고름.
CREATE TEMPORARY TABLE cat_selections_v87 AS
SELECT MIN(uc.id) AS user_character_id
FROM user_characters uc
JOIN moru_wearers_v87 w ON w.user_id = uc.user_id AND w.cat_character_id = uc.character_id
WHERE uc.deleted_at IS NULL
GROUP BY uc.user_id;

UPDATE user_characters
SET is_selected = TRUE, updated_at = CURRENT_TIMESTAMP
WHERE id IN (SELECT user_character_id FROM cat_selections_v87);

UPDATE characters SET is_active = FALSE WHERE code = 'moru';

-- 같은 연결을 재사용해도 다시 실행할 수 있게 임시 테이블을 정리함. H2(admin 테스트)는 DROP TEMPORARY 를 모르므로 DROP TABLE 을 씀.
-- MySQL 에서 DROP TABLE 은 암묵 커밋을 일으키므로 모든 데이터 변경 뒤에 둠.
DROP TABLE cat_selections_v87;
DROP TABLE moru_wearers_v87;
