package com.triples.rougether.userapi.bugreport.error;

import com.triples.rougether.common.error.ErrorCode;

public enum BugReportErrorCode implements ErrorCode {

    BUG_REPORT_IMAGE_INVALID(
            "BUG_REPORT_IMAGE_INVALID", "스크린샷은 png·jpeg·webp 형식, 각 10MB 이하, 최대 3장까지 허용됩니다.", 400);

    private final String code;
    private final String message;
    private final int status;

    BugReportErrorCode(String code, String message, int status) {
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
