package com.triples.rougether.common.error;

import java.util.List;

public record ErrorResponse(String code, String message, List<FieldError> fieldErrors) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null);
    }

    public static ErrorResponse of(String code, String message, List<FieldError> fieldErrors) {
        return new ErrorResponse(code, message, fieldErrors);
    }

    public record FieldError(String field, String reason) {
    }
}
