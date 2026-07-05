package com.triples.rougether.userapi.category.error;

import com.triples.rougether.common.error.ErrorCode;

public enum CategoryErrorCode implements ErrorCode {

    // 타인 소유도 404로 통일함(존재 노출 회피)
    CATEGORY_NOT_FOUND("CATEGORY_NOT_FOUND", "카테고리를 찾을 수 없습니다.", 404),
    CATEGORY_IN_USE("CATEGORY_IN_USE", "이 카테고리를 사용하는 루틴이 있어 삭제할 수 없습니다.", 409);

    private final String code;
    private final String message;
    private final int status;

    CategoryErrorCode(String code, String message, int status) {
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
