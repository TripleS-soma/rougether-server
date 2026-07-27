-- 친구 초대 보상.
-- 집 초대코드(house.invite_code)와 별개로, 사용자 개인 초대코드를 둔다.
-- 집 코드는 집 단위라 "누가 초대했는지"를 특정할 수 없어 개인 코드가 따로 필요하다.

-- 개인 초대코드: 사용자당 1개, 만료 없음. 최초 조회 시 발급(lazy).
CREATE TABLE user_invite_codes (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    code       VARCHAR(20) NOT NULL,
    created_at TIMESTAMP   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_user_invite_codes_user UNIQUE (user_id),
    CONSTRAINT uq_user_invite_codes_code UNIQUE (code),
    CONSTRAINT fk_user_invite_codes_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- 초대 보상 지급 이력.
-- UNIQUE(invitee_user_id) 가 "한 사용자는 평생 1회만 초대 보상 대상"의 DB 방어선이다.
-- 동시에 같은 코드를 여러 번 입력하거나, 서로 다른 코드를 연달아 입력해도 이 제약에서 걸린다.
CREATE TABLE invite_rewards (
    id                     BIGINT    NOT NULL AUTO_INCREMENT,
    inviter_user_id        BIGINT    NOT NULL,
    invitee_user_id        BIGINT    NOT NULL,
    inviter_reward_amount  INT       NOT NULL,
    invitee_reward_amount  INT       NOT NULL,
    created_at             TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_invite_rewards_invitee UNIQUE (invitee_user_id),
    CONSTRAINT fk_invite_rewards_inviter FOREIGN KEY (inviter_user_id) REFERENCES users (id),
    CONSTRAINT fk_invite_rewards_invitee FOREIGN KEY (invitee_user_id) REFERENCES users (id)
);

-- 초대자별 지급 건수 집계(한도 판정)용.
CREATE INDEX idx_invite_rewards_inviter ON invite_rewards (inviter_user_id);
