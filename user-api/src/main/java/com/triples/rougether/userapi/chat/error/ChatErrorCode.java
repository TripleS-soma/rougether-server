package com.triples.rougether.userapi.chat.error;

import com.triples.rougether.common.error.ErrorCode;

public enum ChatErrorCode implements ErrorCode {
    CHAT_ROOM_NOT_FOUND("채팅방을 찾을 수 없습니다.", 404),
    CHAT_FORBIDDEN("이 채팅방에 접근할 수 없습니다.", 403),
    CHAT_INPUT_INVALID("채팅 입력값이 올바르지 않습니다.", 400),
    CHAT_CONTENT_BANNED("메시지에 사용할 수 없는 단어가 포함되어 있습니다.", 400),
    CHAT_MESSAGE_CONFLICT("같은 요청 식별자로 다른 메시지를 보낼 수 없습니다.", 409),
    CHAT_MESSAGE_NOT_FOUND("메시지를 찾을 수 없습니다.", 404),
    CHAT_IMAGE_INVALID("사진은 JPEG 또는 PNG, 10MB 이하만 허용됩니다.", 400),
    CHAT_IMAGE_NOT_FOUND("사진을 찾을 수 없습니다.", 404),
    CHAT_IMAGE_UNAVAILABLE("사용할 수 없는 사진입니다.", 409),
    CHAT_IMAGE_LIMIT("사진은 하루 50장, 보내지 않은 사진은 30장까지 올릴 수 있습니다.", 429),
    CHAT_STORAGE_UNAVAILABLE("사진 저장소에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.", 503);

    private final String message;
    private final int status;
    ChatErrorCode(String message, int status) { this.message = message; this.status = status; }
    public String code() { return name(); }
    public String message() { return message; }
    public int status() { return status; }
}
