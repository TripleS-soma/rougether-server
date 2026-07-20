-- 집 멤버 원탭 응원 (#173). 멤버 방을 구경하다 응원을 보내면 기록하고 대상에게 알림을 발송한다.
-- 도배 방지: 같은 보낸이->같은 대상, 같은 타입, 같은 날(KST) 1회 - unique 가 동시 요청의 최후 방어선.
-- 기록을 남겨 추후 "받은 응원" 목록·집계로 확장할 수 있다.

CREATE TABLE house_member_cheers (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    house_id       BIGINT      NOT NULL,
    sender_user_id BIGINT      NOT NULL,
    target_user_id BIGINT      NOT NULL,
    cheer_type     VARCHAR(20) NOT NULL,
    cheer_date     DATE        NOT NULL,
    created_at     TIMESTAMP   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_house_member_cheer UNIQUE (sender_user_id, target_user_id, cheer_type, cheer_date),
    CONSTRAINT fk_cheer_house FOREIGN KEY (house_id) REFERENCES house (id),
    CONSTRAINT fk_cheer_sender FOREIGN KEY (sender_user_id) REFERENCES users (id),
    CONSTRAINT fk_cheer_target FOREIGN KEY (target_user_id) REFERENCES users (id)
);
