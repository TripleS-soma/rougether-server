package com.triples.rougether.userapi.market.error;

import com.triples.rougether.common.error.ErrorCode;

public enum MarketErrorCode implements ErrorCode {

    ITEM_NOT_OWNED("MARKET_ITEM_NOT_OWNED", "보유하지 않은 가구입니다.", 403),
    NOT_CREATOR("MARKET_NOT_CREATOR", "가구를 만든 사람만 발행할 수 있습니다.", 403),
    ITEM_NOT_TRADABLE("MARKET_ITEM_NOT_TRADABLE", "거래소에 올릴 수 없는 가구입니다.", 409),
    ASSET_ALREADY_LISTED("MARKET_ASSET_ALREADY_LISTED", "이미 거래소에 발행된 가구입니다.", 409);

    private final String code;
    private final String message;
    private final int status;

    MarketErrorCode(String code, String message, int status) {
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
