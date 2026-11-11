-- 사진 메시지 구분. 기존 메시지는 전부 TEXT임.
ALTER TABLE chat_messages
    ADD COLUMN message_type VARCHAR(20) NOT NULL DEFAULT 'TEXT'
        CONSTRAINT ck_chat_message_type CHECK (message_type IN ('TEXT', 'IMAGE'));
