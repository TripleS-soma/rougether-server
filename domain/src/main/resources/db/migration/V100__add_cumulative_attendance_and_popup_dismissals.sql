-- 기존 연속 출석 기록과 보상은 STREAK 기본값으로 보존함.
ALTER TABLE attendance_events ADD COLUMN mode VARCHAR(20) NOT NULL DEFAULT 'STREAK';
ALTER TABLE attendance_events ADD COLUMN milestone_every INT NOT NULL DEFAULT 0;
ALTER TABLE attendance_events ADD COLUMN milestone_coin_amount INT NOT NULL DEFAULT 0;
ALTER TABLE attendance_events ADD CONSTRAINT ck_attendance_mode CHECK (mode IN ('STREAK', 'CUMULATIVE'));
ALTER TABLE attendance_events ADD CONSTRAINT ck_attendance_milestone CHECK (
    milestone_every BETWEEN 0 AND target_days AND milestone_coin_amount >= 0
);
-- 기존 가구/생성권 출석 보상 조건에 누적 코인 전용 이벤트만 추가함.
ALTER TABLE attendance_events DROP CONSTRAINT chk_attendance_reward_kind;
ALTER TABLE attendance_events ADD CONSTRAINT chk_attendance_reward_kind CHECK (
    (reward_item_id IS NOT NULL AND generation_credit_amount = 0)
    OR (reward_item_id IS NULL AND generation_credit_amount = 1 AND target_days = 7 AND mode = 'STREAK')
    OR (reward_item_id IS NULL AND generation_credit_amount = 0 AND mode = 'CUMULATIVE')
);
-- 진척 완료는 특별 아이템 지급 여부와 분리해 기록함. 기존 지급 제약은 그대로 보존함.
ALTER TABLE attendance_check_ins ADD COLUMN progress_completed_at TIMESTAMP NULL;

-- 문구/이미지 수정과 무관하게 계정과 이벤트 코드 단위로 영구 숨김을 보존함.
CREATE TABLE event_popup_dismissals (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    event_code VARCHAR(50) NOT NULL,
    dismissed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_event_popup_dismissal_user_code UNIQUE (user_id, event_code),
    CONSTRAINT fk_event_popup_dismissal_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
