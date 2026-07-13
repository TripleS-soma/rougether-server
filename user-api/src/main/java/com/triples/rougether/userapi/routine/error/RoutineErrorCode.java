package com.triples.rougether.userapi.routine.error;

import com.triples.rougether.common.error.ErrorCode;

public enum RoutineErrorCode implements ErrorCode {

    // 타인 소유도 404로 통일함(존재 노출 회피)
    ROUTINE_NOT_FOUND("ROUTINE_NOT_FOUND", "루틴을 찾을 수 없습니다.", 404),
    // BIWEEKLY는 startsOn이 속한 주를 1주차 기준으로 삼으므로 startsOn 필수
    BIWEEKLY_REQUIRES_STARTS_ON("BIWEEKLY_REQUIRES_STARTS_ON", "격주 반복은 시작일(startsOn)이 필요합니다.", 400);

    private final String code;
    private final String message;
    private final int status;

    RoutineErrorCode(String code, String message, int status) {
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
