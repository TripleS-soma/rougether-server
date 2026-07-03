package com.triples.rougether.userapi.member.error;

import com.triples.rougether.common.error.ErrorCode;

public enum MemberErrorCode implements ErrorCode {

    GOAL_REQUIRED("GOAL_REQUIRED", "목표를 최소 1개 선택해야 합니다.", 400),
    INVALID_GOAL("INVALID_GOAL", "존재하지 않거나 비활성 목표입니다.", 400),
    PRIMARY_GOAL_NOT_IN_SELECTION("PRIMARY_GOAL_NOT_IN_SELECTION", "대표 목표는 선택한 목표에 포함되어야 합니다.", 400);

    private final String code;
    private final String message;
    private final int status;

    MemberErrorCode(String code, String message, int status) {
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
