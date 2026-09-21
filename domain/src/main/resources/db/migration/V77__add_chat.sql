CREATE TABLE chat_rooms (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    room_type VARCHAR(20) NOT NULL,
    house_id BIGINT NULL,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_chat_room_house UNIQUE (house_id),
    CONSTRAINT fk_chat_room_house FOREIGN KEY (house_id) REFERENCES house(id)
);

CREATE TABLE chat_messages (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    message_sequence BIGINT NOT NULL,
    sender_user_id BIGINT NOT NULL,
    client_message_id VARCHAR(36) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_chat_message_sequence UNIQUE (room_id, message_sequence),
    CONSTRAINT uk_chat_message_retry UNIQUE (room_id, sender_user_id, client_message_id),
    CONSTRAINT fk_chat_message_room FOREIGN KEY (room_id) REFERENCES chat_rooms(id),
    CONSTRAINT fk_chat_message_sender FOREIGN KEY (sender_user_id) REFERENCES users(id)
);

CREATE TABLE chat_read_states (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    last_read_sequence BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_chat_read_user UNIQUE (room_id, user_id),
    CONSTRAINT fk_chat_read_room FOREIGN KEY (room_id) REFERENCES chat_rooms(id),
    CONSTRAINT fk_chat_read_user FOREIGN KEY (user_id) REFERENCES users(id)
);
