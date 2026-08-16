package com.triples.rougether.userapi.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record AttendanceCheckInResponse(
        @Schema(description = "이번 호출에서 새 출석이 기록됐는지", example = "true")
        boolean newCheckIn,
        @Schema(description = "이번 호출에서 지급한 코인. 멱등 재호출이면 0", example = "30")
        int coinRewardAmount,
        @Schema(description = "지급 처리 후 현재 코인 잔액", example = "190")
        int coinBalance,
        @Schema(description = "이번 호출에서 보상 가구가 새로 지급됐는지", example = "false")
        boolean rewardGrantedNow,
        AttendanceEventStatusResponse status) {
}
