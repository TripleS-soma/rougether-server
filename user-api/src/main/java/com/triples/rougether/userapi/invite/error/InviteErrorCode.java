package com.triples.rougether.userapi.invite.error;

import com.triples.rougether.common.error.ErrorCode;

public enum InviteErrorCode implements ErrorCode {

    INVITE_CODE_NOT_FOUND("INVITE_CODE_NOT_FOUND", "유효하지 않은 초대코드입니다.", 404),
    INVITE_SELF_NOT_ALLOWED("INVITE_SELF_NOT_ALLOWED", "자신의 초대코드는 사용할 수 없습니다.", 400),
    INVITE_ALREADY_REDEEMED("INVITE_ALREADY_REDEEMED", "이미 초대 보상을 받았습니다.", 409);

    private final String code;
    private final String message;
    private final int status;

    InviteErrorCode(String code, String message, int status) {
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
