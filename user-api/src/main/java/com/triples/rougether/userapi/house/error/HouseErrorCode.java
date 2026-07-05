package com.triples.rougether.userapi.house.error;

import com.triples.rougether.common.error.ErrorCode;

public enum HouseErrorCode implements ErrorCode {

    HOUSE_GOAL_INVALID("HOUSE_GOAL_INVALID", "존재하지 않거나 비활성인 목표가 포함되어 있습니다.", 400),
    HOUSE_NOT_FOUND("HOUSE_NOT_FOUND", "존재하지 않는 집입니다.", 404),
    HOUSE_NOT_MEMBER("HOUSE_NOT_MEMBER", "이 집의 구성원이 아닙니다.", 403),
    HOUSE_NOT_OWNER("HOUSE_NOT_OWNER", "집 소유자만 할 수 있습니다.", 403),
    HOUSE_TRANSFER_TARGET_INVALID("HOUSE_TRANSFER_TARGET_INVALID", "양도 대상이 이 집의 다른 활성 구성원이 아닙니다.", 400),
    HOUSE_OWNER_MUST_TRANSFER("HOUSE_OWNER_MUST_TRANSFER", "소유자는 소유권을 양도한 뒤 탈퇴할 수 있습니다.", 409),
    INVITE_CODE_INVALID("INVITE_CODE_INVALID", "유효하지 않은 초대코드입니다.", 404),
    INVITE_CODE_EXPIRED("INVITE_CODE_EXPIRED", "만료된 초대코드입니다.", 409),
    HOUSE_FULL("HOUSE_FULL", "집 정원이 가득 찼습니다.", 409),
    HOUSE_ALREADY_MEMBER("HOUSE_ALREADY_MEMBER", "이미 참여 중인 집입니다.", 409);

    private final String code;
    private final String message;
    private final int status;

    HouseErrorCode(String code, String message, int status) {
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
