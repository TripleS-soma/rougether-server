package com.triples.rougether.userapi.member.error;

import com.triples.rougether.common.error.ErrorCode;

public enum MemberErrorCode implements ErrorCode {

    GOAL_REQUIRED("GOAL_REQUIRED", "목표를 최소 1개 선택해야 합니다.", 400),
    INVALID_GOAL("INVALID_GOAL", "존재하지 않거나 비활성 목표입니다.", 400),
    PRIMARY_GOAL_NOT_IN_SELECTION("PRIMARY_GOAL_NOT_IN_SELECTION", "대표 목표는 선택한 목표에 포함되어야 합니다.", 400),
    CHARACTER_NOT_FOUND("CHARACTER_NOT_FOUND", "존재하지 않거나 비활성 캐릭터입니다.", 404),
    CHARACTER_NOT_OWNED("CHARACTER_NOT_OWNED", "보유하지 않은 캐릭터입니다.", 409),
    USER_NOT_FOUND("USER_NOT_FOUND", "회원을 찾을 수 없습니다.", 404),
    MEMBER_PROFILE_IMAGE_INVALID(
            "MEMBER_PROFILE_IMAGE_INVALID", "프로필 사진은 png·jpeg·webp 형식, 10MB 이하만 허용됩니다.", 400);

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
