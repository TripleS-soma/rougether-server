package com.triples.rougether.userapi.character.error;

import com.triples.rougether.common.error.ErrorCode;

public enum CharacterAccessoryErrorCode implements ErrorCode {

    ACCESSORY_NOT_OWNED(
            "CHARACTER_ACCESSORY_NOT_OWNED", "보유하지 않은 악세사리는 적용할 수 없습니다.", 403),
    ACCESSORY_INVALID(
            "CHARACTER_ACCESSORY_INVALID", "적용할 수 없는 캐릭터 악세사리입니다.", 409),
    ACCESSORY_UNSUPPORTED_CHARACTER(
            "CHARACTER_ACCESSORY_UNSUPPORTED_CHARACTER",
            "이 캐릭터에는 해당 악세사리를 적용할 수 없습니다.", 409);

    private final String code;
    private final String message;
    private final int status;

    CharacterAccessoryErrorCode(String code, String message, int status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public int status() {
        return status;
    }
}
