package com.triples.rougether.userapi.attendance.error;

import com.triples.rougether.common.error.ErrorCode;

public enum AttendanceErrorCode implements ErrorCode {
    ATTENDANCE_EVENT_NOT_FOUND("ATTENDANCE_EVENT_NOT_FOUND", "진행 중인 출석 이벤트가 없습니다.", 404),
    ATTENDANCE_EVENT_CONFIGURATION_INVALID(
            "ATTENDANCE_EVENT_CONFIGURATION_INVALID", "출석 이벤트 설정이 올바르지 않습니다.", 500);

    private final String code;
    private final String message;
    private final int status;

    AttendanceErrorCode(String code, String message, int status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    @Override public String code() { return code; }
    @Override public String message() { return message; }
    @Override public int status() { return status; }
}
