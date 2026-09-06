package com.triples.rougether.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    // ErrorResponse.details 로 그대로 내려가는 구조화 부가정보. 없으면 null.
    private final Map<String, Object> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.message(), null);
    }

    // 같은 코드를 상황별 메시지로 구분해야 할 때 사용 (예: ALREADY_CLAIMED 의 WEEKLY/DAILY 구분, #201)
    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    // 코드·메시지만으로 클라이언트가 분기할 수 없을 때 details 를 실음
    // (예: 같은 이메일 타 provider 계정 안내의 providers 목록).
    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        // Map.copyOf 는 null 값을 거부해 원래 에러가 NPE 로 뒤바뀔 수 있어 방어적으로 복사함.
        this.details = details == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
