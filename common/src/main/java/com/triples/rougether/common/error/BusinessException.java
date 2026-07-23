package com.triples.rougether.common.error;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    // 같은 코드를 상황별 메시지로 구분해야 할 때 사용 (예: ALREADY_CLAIMED 의 WEEKLY/DAILY 구분, #201)
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
