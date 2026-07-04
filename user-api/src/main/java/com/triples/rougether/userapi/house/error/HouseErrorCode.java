package com.triples.rougether.userapi.house.error;

import com.triples.rougether.common.error.ErrorCode;

public enum HouseErrorCode implements ErrorCode {

    HOUSE_GOAL_INVALID("HOUSE_GOAL_INVALID", "존재하지 않거나 비활성인 목표가 포함되어 있습니다.", 400);

    private final String code;
    private final String message;
    private final int status;

    HouseErrorCode(String code, String message, int status) {
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
