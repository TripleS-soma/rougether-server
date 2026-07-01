package com.triples.rougether.userapi.gacha.error;

import com.triples.rougether.common.error.ErrorCode;

public enum GachaErrorCode implements ErrorCode {

    GACHA_NOT_FOUND("GACHA_NOT_FOUND", "존재하지 않는 뽑기입니다.", 404),
    GACHA_INACTIVE("GACHA_INACTIVE", "현재 운영 중이 아닌 뽑기입니다.", 409),
    INVALID_DRAW_COUNT("GACHA_INVALID_DRAW_COUNT", "뽑기 횟수는 1 또는 10만 가능합니다.", 400),
    INSUFFICIENT_COIN("GACHA_INSUFFICIENT_COIN", "코인이 부족합니다.", 409),
    EMPTY_POOL("GACHA_EMPTY_POOL", "뽑기 풀이 비어 있습니다.", 409);

    private final String code;
    private final String message;
    private final int status;

    GachaErrorCode(String code, String message, int status) {
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
