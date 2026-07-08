package com.triples.rougether.userapi.house.error;

import com.triples.rougether.common.error.ErrorCode;

public enum HouseErrorCode implements ErrorCode {

    HOUSE_GOAL_INVALID("HOUSE_GOAL_INVALID", "존재하지 않거나 비활성인 목표가 포함되어 있습니다.", 400),
    HOUSE_NOT_FOUND("HOUSE_NOT_FOUND", "존재하지 않는 집입니다.", 404),
    HOUSE_NOT_MEMBER("HOUSE_NOT_MEMBER", "이 집의 구성원이 아닙니다.", 403),
    HOUSE_NOT_OWNER("HOUSE_NOT_OWNER", "집 소유자만 할 수 있습니다.", 403),
    HOUSE_TRANSFER_TARGET_INVALID("HOUSE_TRANSFER_TARGET_INVALID", "양도 대상이 이 집의 다른 활성 구성원이 아닙니다.", 400),
    HOUSE_OWNER_MUST_TRANSFER("HOUSE_OWNER_MUST_TRANSFER", "소유자는 소유권을 양도한 뒤 탈퇴할 수 있습니다.", 409),
    HOUSE_KICKED_MEMBER("HOUSE_KICKED_MEMBER", "강퇴된 집에는 다시 참여할 수 없습니다.", 409),
    HOUSE_KICK_SELF("HOUSE_KICK_SELF", "자기 자신은 강퇴할 수 없습니다. 탈퇴를 사용하세요.", 400),
    HOUSE_MEMBER_NOT_FOUND("HOUSE_MEMBER_NOT_FOUND", "이 집의 활성 구성원이 아닙니다.", 404),
    HOUSE_ACTIVITY_PERIOD_INVALID("HOUSE_ACTIVITY_PERIOD_INVALID",
            "조회 기간이 올바르지 않습니다. from은 to보다 뒤일 수 없고 기간은 최대 92일입니다.", 400),
    HOUSE_MAX_MEMBERS_BELOW_CURRENT("HOUSE_MAX_MEMBERS_BELOW_CURRENT", "최대 인원은 현재 구성원 수보다 작게 줄일 수 없습니다.", 409),
    HOUSE_MISSION_NOT_FOUND("HOUSE_MISSION_NOT_FOUND", "존재하지 않는 미션입니다.", 404),
    HOUSE_MISSION_TYPE_NOT_SUPPORTED("HOUSE_MISSION_TYPE_NOT_SUPPORTED", "아직 지원하지 않는 미션 유형입니다.", 400),
    HOUSE_MISSION_PERIOD_INVALID("HOUSE_MISSION_PERIOD_INVALID", "미션 종료 시각은 시작 시각보다 뒤여야 합니다.", 400),
    HOUSE_MISSION_NOT_ACTIVE("HOUSE_MISSION_NOT_ACTIVE", "진행 중인 미션이 아니거나 기여 가능 기간이 아닙니다.", 409),
    HOUSE_MISSION_ALREADY_CONTRIBUTED("HOUSE_MISSION_ALREADY_CONTRIBUTED", "오늘은 이미 기여했습니다.", 409),
    HOUSE_MISSION_NOT_ACHIEVED("HOUSE_MISSION_NOT_ACHIEVED", "아직 목표를 달성하지 못했습니다.", 409),
    HOUSE_MISSION_ALREADY_CLAIMED("HOUSE_MISSION_ALREADY_CLAIMED", "이미 보상을 받은 미션입니다.", 409),
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
