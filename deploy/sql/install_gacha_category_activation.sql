-- MySQL 8 운영용 임시 루틴 설치 파일임. 실행만으로 머신을 활성화하지 않음.
-- 반드시 docs/gacha-category-rollout.md의 승인/관측 절차를 먼저 수행함.
-- CREATE/DROP PROCEDURE는 DDL이므로 CALL의 데이터 트랜잭션과 별개임.
-- 기존 루틴을 덮어쓰지 않음. 이미 존재하면 중단 후 정의/실행 이력을 검토함.
-- CALL은 진행 중 트랜잭션이 없는 전용 연결에서 실행함. --force 옵션 사용 금지.
DELIMITER $$
CREATE PROCEDURE activate_gacha_categories_v66(
    IN p_wallpaper_id BIGINT, IN p_floor_id BIGINT, IN p_furniture_id BIGINT,
    IN p_wallpaper_pool INT, IN p_floor_pool INT, IN p_furniture_pool INT,
    IN p_expected_sha VARCHAR(64), IN p_observed_sha VARCHAR(64),
    IN p_deployment_ready BOOLEAN, IN p_public_health_ok BOOLEAN, IN p_old_slots_drained BOOLEAN
)
SQL SECURITY INVOKER
MODIFIES SQL DATA
BEGIN
    DECLARE v_count INT DEFAULT 0;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    -- DB에서 외부 배포를 실측할 수는 없음. 운영자가 증빙으로 확인한 값만 전달함.
    IF p_expected_sha IS NULL OR p_observed_sha IS NULL
       OR NOT REGEXP_LIKE(p_expected_sha, '^[0-9a-f]{40}$', 'c')
       OR NOT (BINARY p_expected_sha <=> BINARY p_observed_sha)
       OR NOT (p_deployment_ready <=> TRUE)
       OR NOT (p_public_health_ok <=> TRUE)
       OR NOT (p_old_slots_drained <=> TRUE)
       OR COALESCE(p_wallpaper_pool, 0) < 1
       OR COALESCE(p_floor_pool, 0) < 1
       OR COALESCE(p_furniture_pool, 0) < 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'gacha category activation: release preconditions mismatch';
    END IF;

    -- 행/범위 잠금을 유지하여 검증 뒤 동시 풀·아이템·테마 변경이 끼어들지 못하게 함.
    SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;
    START TRANSACTION;
    UPDATE gacha SET is_active = is_active
    WHERE code IN ('wallpaper_gacha', 'floor_gacha', 'furniture_gacha')
    ORDER BY id;

    SELECT COUNT(*) INTO v_count FROM gacha
    WHERE code IN ('wallpaper_gacha', 'floor_gacha', 'furniture_gacha');
    IF v_count <> 3 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'gacha category activation: expected exactly three canonical rows';
    END IF;
    SELECT COUNT(*) INTO v_count FROM gacha
    WHERE (BINARY code = 'wallpaper_gacha' AND id = p_wallpaper_id)
       OR (BINARY code = 'floor_gacha' AND id = p_floor_id)
       OR (BINARY code = 'furniture_gacha' AND id = p_furniture_id);
    IF v_count <> 3 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'gacha category activation: exact machine IDs/codes mismatch';
    END IF;

    SELECT COUNT(*) INTO v_count FROM gacha
    WHERE id IN (p_wallpaper_id, p_floor_id, p_furniture_id)
      AND is_active = FALSE AND theme_id IS NULL AND starts_at IS NULL AND ends_at IS NULL
      AND BINARY cost_currency_type = 'COIN' AND cost_amount = 25 AND draw_count = 1;
    IF v_count <> 3 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'gacha category activation: machine state/price mismatch';
    END IF;

    SELECT COUNT(*) INTO v_count FROM gacha_pool_entries
    WHERE gacha_id = p_wallpaper_id AND is_active = TRUE;
    IF v_count <> p_wallpaper_pool THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'gacha category activation: wallpaper pool count mismatch';
    END IF;
    SELECT COUNT(*) INTO v_count FROM gacha_pool_entries
    WHERE gacha_id = p_floor_id AND is_active = TRUE;
    IF v_count <> p_floor_pool THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'gacha category activation: floor pool count mismatch';
    END IF;
    SELECT COUNT(*) INTO v_count FROM gacha_pool_entries
    WHERE gacha_id = p_furniture_id AND is_active = TRUE;
    IF v_count <> p_furniture_pool THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'gacha category activation: furniture pool count mismatch';
    END IF;

    -- 활성 엔트리는 모두 실제 배출 가능한 단일 ITEM이어야 함. NULL도 실패로 처리함.
    SELECT COUNT(*) INTO v_count
    FROM gacha_pool_entries e
    LEFT JOIN items i ON i.id = e.item_id
    LEFT JOIN themes t ON t.id = i.theme_id
    WHERE e.gacha_id IN (p_wallpaper_id, p_floor_id, p_furniture_id) AND e.is_active = TRUE
      AND COALESCE(
          BINARY e.reward_type = 'ITEM' AND e.item_id IS NOT NULL
          AND e.character_id IS NULL AND e.currency_type IS NULL AND e.reward_amount IS NULL
          AND e.rarity IN ('일반', '희귀', '전설') AND e.weight = 1
          AND i.is_active = TRUE AND t.is_active = TRUE
          AND i.character_slot_type IS NULL
          AND i.category_code NOT IN ('character_accessory', 'background')
          AND ((e.gacha_id = p_wallpaper_id AND BINARY i.placement_type = 'surface_slot' AND BINARY i.surface_slot_type = 'wallpaper')
            OR (e.gacha_id = p_floor_id AND BINARY i.placement_type = 'surface_slot' AND BINARY i.surface_slot_type = 'floor')
            OR (e.gacha_id = p_furniture_id AND BINARY i.placement_type = 'positioned' AND i.surface_slot_type IS NULL)),
          FALSE) = FALSE;
    IF v_count <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'gacha category activation: ineligible active pool entry';
    END IF;

    SELECT COUNT(*) INTO v_count FROM (
        SELECT gacha_id, item_id FROM gacha_pool_entries
        WHERE gacha_id IN (p_wallpaper_id, p_floor_id, p_furniture_id) AND is_active = TRUE
        GROUP BY gacha_id, item_id HAVING COUNT(*) > 1
    ) duplicates;
    IF v_count <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'gacha category activation: duplicate active pool entry';
    END IF;

    UPDATE gacha SET is_active = TRUE, updated_at = CURRENT_TIMESTAMP
    WHERE id IN (p_wallpaper_id, p_floor_id, p_furniture_id) AND is_active = FALSE
    ORDER BY id;
    IF ROW_COUNT() <> 3 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'gacha category activation: expected exactly three updates';
    END IF;
    SELECT COUNT(*) INTO v_count FROM gacha
    WHERE id IN (p_wallpaper_id, p_floor_id, p_furniture_id) AND is_active = TRUE;
    IF v_count <> 3 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'gacha category activation: postcondition mismatch';
    END IF;
    COMMIT;
END$$

CREATE PROCEDURE deactivate_gacha_categories_v66(
    IN p_wallpaper_id BIGINT, IN p_floor_id BIGINT, IN p_furniture_id BIGINT
)
SQL SECURITY INVOKER
MODIFIES SQL DATA
BEGIN
    DECLARE v_count INT DEFAULT 0;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;
    START TRANSACTION;
    UPDATE gacha SET is_active = is_active
    WHERE code IN ('wallpaper_gacha', 'floor_gacha', 'furniture_gacha')
    ORDER BY id;
    SELECT COUNT(*) INTO v_count FROM gacha
    WHERE code IN ('wallpaper_gacha', 'floor_gacha', 'furniture_gacha');
    IF v_count <> 3 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'gacha category deactivation: expected exactly three canonical rows';
    END IF;
    SELECT COUNT(*) INTO v_count FROM gacha
    WHERE (BINARY code = 'wallpaper_gacha' AND id = p_wallpaper_id)
       OR (BINARY code = 'floor_gacha' AND id = p_floor_id)
       OR (BINARY code = 'furniture_gacha' AND id = p_furniture_id);
    IF v_count <> 3 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'gacha category deactivation: exact machine IDs/codes mismatch';
    END IF;

    -- 비상 복구는 풀/가격 변조와 무관하게 수행할 수 있음. 반복 호출도 안전함.
    UPDATE gacha SET is_active = FALSE, updated_at = CURRENT_TIMESTAMP
    WHERE id IN (p_wallpaper_id, p_floor_id, p_furniture_id) AND is_active = TRUE
    ORDER BY id;
    SELECT COUNT(*) INTO v_count FROM gacha
    WHERE id IN (p_wallpaper_id, p_floor_id, p_furniture_id) AND is_active = FALSE;
    IF v_count <> 3 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'gacha category deactivation: postcondition mismatch';
    END IF;
    COMMIT;
END$$
DELIMITER ;
