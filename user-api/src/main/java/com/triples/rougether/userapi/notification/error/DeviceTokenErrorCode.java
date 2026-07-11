package com.triples.rougether.userapi.notification.error;

import com.triples.rougether.common.error.ErrorCode;

public enum DeviceTokenErrorCode implements ErrorCode {

    // 타인 소유도 404로 통일함(존재 노출 회피)
    DEVICE_TOKEN_NOT_FOUND("DEVICE_TOKEN_NOT_FOUND", "디바이스 토큰을 찾을 수 없습니다.", 404);

    private final String code;
    private final String message;
    private final int status;

    DeviceTokenErrorCode(String code, String message, int status) {
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
