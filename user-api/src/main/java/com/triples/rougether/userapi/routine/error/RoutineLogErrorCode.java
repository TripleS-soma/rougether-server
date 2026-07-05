package com.triples.rougether.userapi.routine.error;

import com.triples.rougether.common.error.ErrorCode;

public enum RoutineLogErrorCode implements ErrorCode {

    ALREADY_COMPLETED("ALREADY_COMPLETED", "이미 오늘 완료한 루틴입니다.", 409),
    LOG_NOT_CANCELABLE("LOG_NOT_CANCELABLE", "당일 완료만 취소할 수 있습니다.", 409),
    // 타인 소유도 404로 통일함(존재 노출 회피)
    ROUTINE_LOG_NOT_FOUND("ROUTINE_LOG_NOT_FOUND", "완료 기록을 찾을 수 없습니다.", 404),
    WALLET_NOT_FOUND("WALLET_NOT_FOUND", "지갑을 찾을 수 없습니다.", 404),
    INVALID_ROUTINE_DATE("INVALID_ROUTINE_DATE", "오늘 날짜만 완료할 수 있습니다.", 400);

    private final String code;
    private final String message;
    private final int status;

    RoutineLogErrorCode(String code, String message, int status) {
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
