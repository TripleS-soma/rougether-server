package com.triples.rougether.common.error;

import java.util.List;
import java.util.Map;

// 에러 응답 공통 형식 { code, message, fieldErrors, details }.
// details 는 code·message 만으로 클라이언트가 분기할 수 없을 때 싣는 구조화 부가정보
// (예: 409 AUTH_EMAIL_LINKED_TO_OTHER_PROVIDER 의 providers). 대부분의 에러는 null 이다.
public record ErrorResponse(String code, String message, List<FieldError> fieldErrors, Map<String, Object> details) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null, null);
    }

    public static ErrorResponse of(String code, String message, List<FieldError> fieldErrors) {
        return new ErrorResponse(code, message, fieldErrors, null);
    }

    public static ErrorResponse withDetails(String code, String message, Map<String, Object> details) {
        return new ErrorResponse(code, message, null, details);
    }

    public record FieldError(String field, String reason) {
    }
}
