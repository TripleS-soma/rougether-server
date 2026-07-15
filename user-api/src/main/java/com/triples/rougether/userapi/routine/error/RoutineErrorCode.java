package com.triples.rougether.userapi.routine.error;

import com.triples.rougether.common.error.ErrorCode;

public enum RoutineErrorCode implements ErrorCode {

    // 타인 소유도 404로 통일함(존재 노출 회피)
    ROUTINE_NOT_FOUND("ROUTINE_NOT_FOUND", "루틴을 찾을 수 없습니다.", 404),

    // 스냅샷 구조상 루틴은 생성일부터 존재 → 시작일을 생성일 이전으로 소급 불가
    ROUTINE_STARTS_ON_BEFORE_TODAY("ROUTINE_STARTS_ON_BEFORE_TODAY", "시작일은 오늘 이전으로 설정할 수 없습니다.", 400);

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
