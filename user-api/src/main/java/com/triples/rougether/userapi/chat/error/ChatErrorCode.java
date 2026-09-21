package com.triples.rougether.userapi.chat.error;

import com.triples.rougether.common.error.ErrorCode;

public enum ChatErrorCode implements ErrorCode {
    CHAT_ROOM_NOT_FOUND("채팅방을 찾을 수 없습니다.", 404),
    CHAT_FORBIDDEN("이 채팅방에 접근할 수 없습니다.", 403),
    CHAT_INPUT_INVALID("채팅 입력값이 올바르지 않습니다.", 400),
    CHAT_CONTENT_BANNED("메시지에 사용할 수 없는 단어가 포함되어 있습니다.", 400),
    CHAT_MESSAGE_CONFLICT("같은 요청 식별자로 다른 메시지를 보낼 수 없습니다.", 409);

    private final String message;
    private final int status;
    ChatErrorCode(String message, int status) { this.message = message; this.status = status; }
    public String code() { return name(); }
    public String message() { return message; }
    public int status() { return status; }
}
