package com.triples.rougether.adminapi.activity.error;

import com.triples.rougether.common.error.ErrorCode;

public enum AdminActivityErrorCode implements ErrorCode {
    DATE_INVALID("ADMIN_ACTIVITY_DATE_INVALID", "활동 조회 날짜는 KST 오늘부터 최근 365일 이내여야 합니다.", 400),
    BUSY("ADMIN_ACTIVITY_BUSY", "다른 활동 집계를 처리 중입니다. 잠시 후 다시 조회해 주세요.", 503);

    private final String code;
    private final String message;
    private final int status;

    AdminActivityErrorCode(String code, String message, int status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    public String code() { return code; }
    public String message() { return message; }
    public int status() { return status; }
}
