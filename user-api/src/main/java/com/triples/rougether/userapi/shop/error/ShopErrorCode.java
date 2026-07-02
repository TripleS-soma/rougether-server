package com.triples.rougether.userapi.shop.error;

import com.triples.rougether.common.error.ErrorCode;

public enum ShopErrorCode implements ErrorCode {

    ITEM_NOT_FOUND("SHOP_ITEM_NOT_FOUND", "존재하지 않는 아이템입니다.", 404),
    ITEM_NOT_PURCHASABLE("SHOP_ITEM_NOT_PURCHASABLE", "구매할 수 없는 아이템입니다.", 409),
    ALREADY_OWNED("SHOP_ALREADY_OWNED", "이미 보유한 아이템입니다.", 409),
    INSUFFICIENT_BALANCE("SHOP_INSUFFICIENT_BALANCE", "재화가 부족합니다.", 409);

    private final String code;
    private final String message;
    private final int status;

    ShopErrorCode(String code, String message, int status) {
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
