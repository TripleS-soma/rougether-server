-- 장기 미접속 개인 방의 거미줄 상태. 방마다 행 1개를 재사용해 청소 후 재발생을 지원한다.
CREATE TABLE room_cobwebs (
    room_user_id       BIGINT    NOT NULL,
    appeared_at        TIMESTAMP NOT NULL,
    cleaned_at         TIMESTAMP NULL,
    cleaned_by_user_id BIGINT    NULL,
    updated_at         TIMESTAMP NOT NULL,
    PRIMARY KEY (room_user_id),
    CONSTRAINT fk_room_cobwebs_room FOREIGN KEY (room_user_id) REFERENCES personal_rooms (user_id),
    CONSTRAINT fk_room_cobwebs_cleaner FOREIGN KEY (cleaned_by_user_id) REFERENCES users (id)
);

CREATE INDEX idx_room_cobwebs_cleaned_at ON room_cobwebs (cleaned_at);
