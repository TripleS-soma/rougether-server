-- 집 채팅 확장: 사진 여러 장, 사진 설명 글, 이모티콘, 공감.
-- 새 컬럼은 NULL 허용이거나 DEFAULT가 있어 배포 전환 중 구버전 코드의 INSERT가 그대로 동작함.

-- caption: 사진 메시지의 설명 글 원문. content는 구버전 앱 표시용 문구로 계속 NOT NULL임.
ALTER TABLE chat_messages ADD COLUMN caption VARCHAR(2000) NULL;
ALTER TABLE chat_messages ADD COLUMN emoticon_key VARCHAR(64) NULL;

ALTER TABLE chat_messages DROP CONSTRAINT ck_chat_message_type;
ALTER TABLE chat_messages ADD CONSTRAINT ck_chat_message_type
    CHECK (message_type IN ('TEXT', 'IMAGE', 'EMOTICON'));

-- 메시지 하나에 사진 여러 장을 붙임. sort_order는 요청에 적힌 순서(0부터)임.
-- uk_chat_image_message는 FK(fk_chat_image_message)가 쓰는 인덱스임. H2는 FK가 남아 있으면 제약만 지우고
-- 유일 인덱스를 남기므로, MySQL·H2 모두에서 같은 결과가 되도록 FK를 내렸다가 대체 인덱스를 만든 뒤 다시 검.
ALTER TABLE chat_images ADD COLUMN sort_order INT NOT NULL DEFAULT 0;
ALTER TABLE chat_images DROP CONSTRAINT fk_chat_image_message;
ALTER TABLE chat_images DROP CONSTRAINT uk_chat_image_message;
CREATE INDEX idx_chat_image_message_order ON chat_images (message_id, sort_order);
ALTER TABLE chat_images ADD CONSTRAINT fk_chat_image_message
    FOREIGN KEY (message_id) REFERENCES chat_messages(id);

-- 메시지 공감. 한 사람이 메시지당 하나이고 유형을 바꾸면 같은 행을 고침.
CREATE TABLE chat_message_reactions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    reaction_type VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_chat_reaction_message_user UNIQUE (message_id, user_id),
    CONSTRAINT fk_chat_reaction_message FOREIGN KEY (message_id) REFERENCES chat_messages(id),
    CONSTRAINT fk_chat_reaction_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT ck_chat_reaction_type
        CHECK (reaction_type IN ('HEART', 'LIKE', 'CHECK', 'LAUGH', 'SURPRISE', 'SAD')),
    INDEX idx_chat_reaction_room (room_id)
);
