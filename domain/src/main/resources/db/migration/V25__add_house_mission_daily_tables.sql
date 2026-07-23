-- 단체 미션 DAILY 일일 달성 모델 (#201). DAILY_MEMBER_RATE 를 실제 일일 미션(매일 판정·반복 보상)으로 만든다.
-- 일별 기여 이력: DAILY·WEEKLY 공통 기록. 하루(KST) 1회 기여를 updated_at 추정 대신 unique 로 강제한다.
-- 일별 보상 이력: DAILY 미션의 하루 1회 claim 방어선. 미션 행 락과 함께 동시 claim 이중 지급을 막는다.

CREATE TABLE house_mission_daily_contributions (
    id                BIGINT    NOT NULL AUTO_INCREMENT,
    mission_id        BIGINT    NOT NULL,
    membership_id     BIGINT    NOT NULL,
    contribution_date DATE      NOT NULL,
    created_at        TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_hmdc_mission_member_date UNIQUE (mission_id, membership_id, contribution_date),
    CONSTRAINT fk_hmdc_mission FOREIGN KEY (mission_id) REFERENCES house_missions (id),
    CONSTRAINT fk_hmdc_membership FOREIGN KEY (membership_id) REFERENCES house_members (id)
);

CREATE TABLE house_mission_daily_rewards (
    id                    BIGINT    NOT NULL AUTO_INCREMENT,
    mission_id            BIGINT    NOT NULL,
    reward_date           DATE      NOT NULL,
    claimed_membership_id BIGINT    NOT NULL,
    created_at            TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_hmdr_mission_date UNIQUE (mission_id, reward_date),
    CONSTRAINT fk_hmdr_mission FOREIGN KEY (mission_id) REFERENCES house_missions (id),
    CONSTRAINT fk_hmdr_membership FOREIGN KEY (claimed_membership_id) REFERENCES house_members (id)
);
