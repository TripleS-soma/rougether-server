package com.triples.rougether.adminapi.moderation.error;

// 신고 처리(#399) 도메인 예외. 컨트롤러 로컬 핸들러가 공통 ErrorResponse 로 변환함.
public class ContentReportAdminException extends RuntimeException {

    private final String code;
    private final int status;

    public ContentReportAdminException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public int getStatus() {
        return status;
    }
}
