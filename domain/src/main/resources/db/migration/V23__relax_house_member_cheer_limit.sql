-- 응원 한도 완화: 같은 보낸이->대상·타입·날짜(KST) 1회 -> 5회.
-- daily_seq(1..5)를 유니크 키에 넣어 한도 초과를 DB 가 최후 방어선으로 막는다(앱은 count 로 사전 거부).
-- 기존 유니크는 sender_user_id FK 가 인덱스로 쓰고 있어, 새 유니크를 먼저 만들고 제거한다.

ALTER TABLE house_member_cheers
    ADD COLUMN daily_seq INT NOT NULL DEFAULT 1 AFTER cheer_date;

ALTER TABLE house_member_cheers
    ADD CONSTRAINT uq_house_member_cheer_seq
        UNIQUE (sender_user_id, target_user_id, cheer_type, cheer_date, daily_seq);

ALTER TABLE house_member_cheers
    DROP INDEX uq_house_member_cheer;

ALTER TABLE house_member_cheers
    ALTER COLUMN daily_seq DROP DEFAULT;
