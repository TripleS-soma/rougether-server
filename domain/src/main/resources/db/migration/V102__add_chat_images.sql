-- 집 채팅 사진. 업로드(미전송)부터 메시지 연결까지 한 행으로 관리함.
CREATE TABLE chat_images (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    message_id BIGINT,
    storage_key VARCHAR(255) NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    ready BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_chat_image_key UNIQUE (storage_key),
    CONSTRAINT uk_chat_image_message UNIQUE (message_id),
    CONSTRAINT fk_chat_image_room FOREIGN KEY (room_id) REFERENCES chat_rooms(id),
    CONSTRAINT fk_chat_image_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    CONSTRAINT fk_chat_image_message FOREIGN KEY (message_id) REFERENCES chat_messages(id),
    INDEX idx_chat_image_owner_created (owner_id, created_at),
    INDEX idx_chat_image_room (room_id),
    INDEX idx_chat_image_expiry (expires_at, id)
);
