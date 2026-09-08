DELIMITER //
CREATE PROCEDURE ops_attendance_launch_20260908()
BEGIN
  DECLARE uid BIGINT;
  DECLARE done INT DEFAULT 0;
  DECLARE lock_ok INT DEFAULT 0;
  DECLARE grant_count INT DEFAULT 0;
  DECLARE cur CURSOR FOR SELECT id FROM users WHERE deleted_at IS NULL AND id <= 154 ORDER BY id FOR UPDATE;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; DO RELEASE_LOCK('attendance-launch-20260908'); RESIGNAL; END;
  SELECT GET_LOCK('attendance-launch-20260908', 20) INTO lock_ok;
  IF lock_ok <> 1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='campaign lock unavailable'; END IF;
  START TRANSACTION;
  IF EXISTS (SELECT 1 FROM attendance_events WHERE is_active=TRUE AND starts_on <= '2026-09-28' AND ends_on >= '2026-09-08' AND code <> 'AI_FURNITURE_20260908') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='overlapping attendance event';
  END IF;
  IF EXISTS (SELECT 1 FROM attendance_events WHERE code='AI_FURNITURE_20260908' AND (starts_on <> '2026-09-08' OR ends_on <> '2026-09-28' OR target_days <> 7 OR generation_credit_amount <> 1 OR is_active <> TRUE)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='existing campaign contract mismatch';
  END IF;
  OPEN cur;
  grant_loop: LOOP
    FETCH cur INTO uid;
    IF done THEN LEAVE grant_loop; END IF;
    IF NOT EXISTS (SELECT 1 FROM furniture_credit_entries WHERE user_id=uid AND reference_id='launch-20260908-2' AND reason='PROMOTION_GRANT') THEN
      INSERT INTO furniture_credit_accounts(user_id,account_token,balance,reserved) VALUES(uid,UUID(),0,0) ON DUPLICATE KEY UPDATE user_id=uid;
      UPDATE furniture_credit_accounts SET balance=balance+2 WHERE user_id=uid;
      INSERT INTO furniture_credit_entries(user_id,reference_id,reason,amount,balance_after,created_at)
        SELECT user_id,'launch-20260908-2','PROMOTION_GRANT',2,balance,UTC_TIMESTAMP(6) FROM furniture_credit_accounts WHERE user_id=uid;
      SET grant_count=grant_count+1;
    END IF;
  END LOOP;
  CLOSE cur;
  INSERT INTO attendance_events(code,title,starts_on,ends_on,target_days,daily_coin_amount,bonus_day,bonus_coin_amount,reward_item_id,is_active,created_at,generation_credit_amount)
    SELECT 'AI_FURNITURE_20260908','7일 출석하고 내 가구 만들기','2026-09-08','2026-09-28',7,30,5,50,NULL,TRUE,UTC_TIMESTAMP(),1
    WHERE NOT EXISTS (SELECT 1 FROM attendance_events WHERE code='AI_FURNITURE_20260908');
  COMMIT;
  DO RELEASE_LOCK('attendance-launch-20260908');
  SELECT grant_count AS newly_granted_users;
END//
DELIMITER ;
CALL ops_attendance_launch_20260908();
CALL ops_attendance_launch_20260908();
DROP PROCEDURE ops_attendance_launch_20260908;
SELECT COUNT(*) AS recipients,SUM(amount) AS granted_credits,COUNT(DISTINCT user_id) AS unique_recipients FROM furniture_credit_entries WHERE reference_id='launch-20260908-2' AND reason='PROMOTION_GRANT';
SELECT id,code,starts_on,ends_on,target_days,generation_credit_amount,is_active FROM attendance_events WHERE code='AI_FURNITURE_20260908';
