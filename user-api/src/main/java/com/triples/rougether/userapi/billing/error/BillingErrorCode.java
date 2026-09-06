package com.triples.rougether.userapi.billing.error;
import com.triples.rougether.common.error.ErrorCode;
public enum BillingErrorCode implements ErrorCode {
    BILLING_UNAVAILABLE("현재 생성권을 구매할 수 없습니다.", 503),
    BILLING_PURCHASE_INVALID("구매 내역을 확인할 수 없습니다.", 400),
    BILLING_PURCHASE_PENDING("스토어에서 결제가 완료되면 다시 확인해 주세요.", 409),
    BILLING_PURCHASE_OWNER_MISMATCH("이 계정에 연결된 구매가 아닙니다.", 409),
    BILLING_PRODUCT_UNAVAILABLE("판매 중인 생성권 상품이 아닙니다.", 400),
    BILLING_VERIFICATION_LIMIT("잠시 후 구매 내역을 다시 확인해 주세요.", 429),
    BILLING_NOTIFICATION_INVALID("유효한 스토어 알림이 아닙니다.", 401),
    FURNITURE_CREDITS_REQUIRED("가구 생성권이 필요합니다.", 402);
    private final String message; private final int status;
    BillingErrorCode(String message, int status) { this.message = message; this.status = status; }
    public String code() { return name(); }
    public String message() { return message; }
    public int status() { return status; }
}
