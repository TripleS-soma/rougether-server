CREATE TABLE attendance_events (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    code                VARCHAR(50)  NOT NULL,
    title               VARCHAR(120) NOT NULL,
    starts_on           DATE         NOT NULL,
    ends_on             DATE         NOT NULL,
    target_days         INT          NOT NULL,
    daily_coin_amount   INT          NOT NULL,
    bonus_day           INT          NOT NULL,
    bonus_coin_amount   INT          NOT NULL,
    reward_item_id      BIGINT       NOT NULL,
    is_active           BOOLEAN      NOT NULL,
    created_at          TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_attendance_event_code UNIQUE (code),
    CONSTRAINT ck_attendance_event_dates CHECK (starts_on <= ends_on),
    CONSTRAINT ck_attendance_event_target CHECK (target_days BETWEEN 2 AND 365),
    CONSTRAINT ck_attendance_event_daily_coin CHECK (daily_coin_amount >= 0),
    CONSTRAINT ck_attendance_event_bonus_day CHECK (bonus_day BETWEEN 1 AND target_days),
    CONSTRAINT ck_attendance_event_bonus_coin CHECK (bonus_coin_amount >= 0),
    CONSTRAINT fk_attendance_event_item FOREIGN KEY (reward_item_id) REFERENCES items (id)
);

CREATE INDEX idx_attendance_event_active_dates
    ON attendance_events (is_active, starts_on, ends_on);

CREATE TABLE attendance_check_ins (
    id                    BIGINT    NOT NULL AUTO_INCREMENT,
    event_id              BIGINT    NOT NULL,
    user_id               BIGINT    NOT NULL,
    attendance_date       DATE      NOT NULL,
    streak_day            INT       NOT NULL,
    coin_reward_amount    INT       NOT NULL,
    reward_user_item_id   BIGINT    NULL,
    reward_newly_granted  BOOLEAN   NULL,
    reward_processed_at   TIMESTAMP NULL,
    created_at            TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_attendance_check_in_day UNIQUE (event_id, user_id, attendance_date),
    CONSTRAINT ck_attendance_check_in_streak CHECK (streak_day >= 1),
    CONSTRAINT ck_attendance_check_in_coin CHECK (coin_reward_amount >= 0),
    CONSTRAINT ck_attendance_reward_state CHECK (
        (reward_processed_at IS NULL AND reward_user_item_id IS NULL AND reward_newly_granted IS NULL)
        OR
        (reward_processed_at IS NOT NULL AND reward_user_item_id IS NOT NULL AND reward_newly_granted IS NOT NULL)
    ),
    CONSTRAINT fk_attendance_check_in_event FOREIGN KEY (event_id) REFERENCES attendance_events (id),
    CONSTRAINT fk_attendance_check_in_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_attendance_reward_user_item FOREIGN KEY (reward_user_item_id) REFERENCES user_items (id)
);

CREATE INDEX idx_attendance_check_in_user_event
    ON attendance_check_ins (user_id, event_id, attendance_date);
