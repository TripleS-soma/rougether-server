-- 이미 시작한 가구 보상 이벤트의 기록과 설정은 보존함.
ALTER TABLE attendance_events MODIFY COLUMN reward_item_id BIGINT NULL;
ALTER TABLE attendance_events ADD COLUMN generation_credit_amount INT NOT NULL DEFAULT 0;
ALTER TABLE attendance_events ADD CONSTRAINT chk_attendance_reward_kind CHECK (
    (reward_item_id IS NOT NULL AND generation_credit_amount = 0)
    OR (reward_item_id IS NULL AND generation_credit_amount = 1 AND target_days = 7)
);
ALTER TABLE attendance_check_ins ADD COLUMN generation_credit_amount INT NOT NULL DEFAULT 0;
ALTER TABLE attendance_check_ins DROP CONSTRAINT ck_attendance_reward_state;
ALTER TABLE attendance_check_ins ADD CONSTRAINT ck_attendance_reward_state CHECK (
    (reward_processed_at IS NULL AND reward_user_item_id IS NULL AND reward_newly_granted IS NULL AND generation_credit_amount = 0)
    OR (reward_processed_at IS NOT NULL AND reward_user_item_id IS NOT NULL AND reward_newly_granted IS NOT NULL AND generation_credit_amount = 0)
    OR (reward_processed_at IS NOT NULL AND reward_user_item_id IS NULL AND reward_newly_granted IS NOT NULL AND reward_newly_granted = TRUE AND generation_credit_amount = 1)
);
