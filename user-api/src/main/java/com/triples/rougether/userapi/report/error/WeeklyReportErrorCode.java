package com.triples.rougether.userapi.report.error;

import com.triples.rougether.common.error.ErrorCode;

public enum WeeklyReportErrorCode implements ErrorCode {

    // 타인의 회고 id 도 존재 여부를 노출하지 않기 위해 같은 404 를 쓴다.
    WEEKLY_REPORT_NOT_FOUND("WEEKLY_REPORT_NOT_FOUND", "주간 회고를 찾을 수 없습니다.", 404);

    private final String code;
    private final String message;
    private final int status;

    WeeklyReportErrorCode(String code, String message, int status) {
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
