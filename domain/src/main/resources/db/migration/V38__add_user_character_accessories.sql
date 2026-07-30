-- 캐릭터별 악세사리 착용 상태. 대표 캐릭터를 바꿨다가 돌아와도 캐릭터별 마지막 착용을 유지한다.
CREATE TABLE user_character_accessories (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    user_character_id   BIGINT      NOT NULL,
    user_item_id         BIGINT      NOT NULL,
    character_slot_type VARCHAR(40) NOT NULL,
    equipped_at         TIMESTAMP   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_uchar_accessory_slot UNIQUE (user_character_id, character_slot_type),
    CONSTRAINT uq_uchar_accessory_item UNIQUE (user_character_id, user_item_id),
    CONSTRAINT fk_uchar_accessory_character
        FOREIGN KEY (user_character_id) REFERENCES user_characters (id),
    CONSTRAINT fk_uchar_accessory_item
        FOREIGN KEY (user_item_id) REFERENCES user_items (id)
);

CREATE INDEX idx_uchar_accessory_item ON user_character_accessories (user_item_id);
