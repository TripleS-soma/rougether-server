package com.triples.rougether.userapi.logingift.error;

import com.triples.rougether.common.error.ErrorCode;

public enum LoginGiftErrorCode implements ErrorCode {
    LOGIN_GIFT_CAPTURE_UNAVAILABLE("접속 선물 참여 기록을 저장하지 못했습니다. 다시 시도해 주세요.", 503),
    LOGIN_GIFT_NOT_FOUND("접속 선물 내역이 없습니다.", 404);

    private final String message;
    private final int status;
    LoginGiftErrorCode(String message, int status) { this.message = message; this.status = status; }
    @Override public String code() { return name(); }
    @Override public String message() { return message; }
    @Override public int status() { return status; }
}
