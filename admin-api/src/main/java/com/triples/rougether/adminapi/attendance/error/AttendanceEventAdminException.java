package com.triples.rougether.adminapi.attendance.error;

public class AttendanceEventAdminException extends RuntimeException {

    private final String code;
    private final int status;

    public AttendanceEventAdminException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }
}
