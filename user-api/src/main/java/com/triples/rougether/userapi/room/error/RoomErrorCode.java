package com.triples.rougether.userapi.room.error;

import com.triples.rougether.common.error.ErrorCode;

public enum RoomErrorCode implements ErrorCode {

    INVALID_SLOT_TYPE("ROOM_INVALID_SLOT_TYPE", "유효하지 않은 슬롯 타입입니다.", 400),
    DUPLICATE_SLOT_TYPE("ROOM_DUPLICATE_SLOT_TYPE", "같은 슬롯을 한 요청에 중복 지정할 수 없습니다.", 400),
    ITEM_NOT_OWNED("ROOM_ITEM_NOT_OWNED", "소유하지 않은 아이템은 배치할 수 없습니다.", 403);

    private final String code;
    private final String message;
    private final int status;

    RoomErrorCode(String code, String message, int status) {
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
