package com.triples.rougether.adminapi.bugreport.error;

public class BugReportAdminException extends RuntimeException {

    private final String code;
    private final int status;

    public BugReportAdminException(String code, String message, int status) {
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
