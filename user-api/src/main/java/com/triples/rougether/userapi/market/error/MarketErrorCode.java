package com.triples.rougether.userapi.market.error;

import com.triples.rougether.common.error.ErrorCode;

public enum MarketErrorCode implements ErrorCode {

    ITEM_NOT_OWNED("MARKET_ITEM_NOT_OWNED", "보유하지 않은 가구입니다.", 403),
    NOT_CREATOR("MARKET_NOT_CREATOR", "가구를 만든 사람만 발행할 수 있습니다.", 403),
    ITEM_NOT_TRADABLE("MARKET_ITEM_NOT_TRADABLE", "거래소에 올릴 수 없는 가구입니다.", 409),
    ASSET_ALREADY_LISTED("MARKET_ASSET_ALREADY_LISTED", "이미 거래소에 발행된 가구입니다.", 409),
    ITEM_REVIEW_IN_PROGRESS("MARKET_ITEM_REVIEW_IN_PROGRESS", "가구를 다시 검토하는 중이라 발행할 수 없습니다.", 409),
    ASSET_NOT_FOUND("MARKET_ASSET_NOT_FOUND", "거래소에 없는 가구입니다.", 404),
    ASSET_SUSPENDED("MARKET_ASSET_SUSPENDED", "거래가 정지된 가구입니다.", 409),
    INSUFFICIENT_COIN("MARKET_INSUFFICIENT_COIN", "코인이 부족합니다.", 409),
    ALREADY_HOLDING("MARKET_ALREADY_HOLDING", "이미 보유 중이거나 구매를 기다리는 가구입니다.", 409),
    INSUFFICIENT_SUPPLY("MARKET_INSUFFICIENT_SUPPLY", "남은 발행 재고가 부족합니다.", 409),
    ORDER_NOT_FOUND("MARKET_ORDER_NOT_FOUND", "주문을 찾을 수 없습니다.", 404),
    ORDER_NOT_OPEN("MARKET_ORDER_NOT_OPEN", "이미 끝난 주문입니다.", 409),
    COMMAND_NOT_FOUND("MARKET_COMMAND_NOT_FOUND", "주문 접수 내역을 찾을 수 없습니다.", 404),
    REQUEST_CONFLICT("MARKET_REQUEST_CONFLICT", "같은 요청 ID에 다른 내용을 사용할 수 없습니다.", 409),
    OWN_ORDER_CONFLICT("MARKET_OWN_ORDER_CONFLICT", "이 가구에 대기 중인 내 주문이 있어 반대 주문을 낼 수 없습니다.", 409),
    // 수량 수정(#433)
    ORDER_NOT_AMENDABLE("MARKET_ORDER_NOT_AMENDABLE", "발행 재고 판매 주문만 수량을 고칠 수 있습니다.", 409),
    AMEND_BELOW_FILLED("MARKET_AMEND_BELOW_FILLED", "이미 팔린 수량보다 많게 고쳐야 합니다.", 409),
    // 지금 수량과 같은 수정은 입력 오류로 봄(공통 검증 오류와 같은 응답 코드)
    AMEND_SAME_QUANTITY("VALIDATION_FAILED", "지금 수량과 다른 수량을 입력해 주세요.", 400);

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
