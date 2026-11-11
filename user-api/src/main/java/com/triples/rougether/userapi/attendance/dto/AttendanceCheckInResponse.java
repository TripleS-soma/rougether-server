package com.triples.rougether.userapi.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record AttendanceCheckInResponse(
        @Schema(description = "이번 호출에서 새 출석이 기록됐는지", example = "true")
        boolean newCheckIn,
        @Schema(description = "이번 호출에서 지급한 코인. 멱등 재호출이면 0", example = "30")
        int coinRewardAmount,
        @Schema(description = "지급 처리 후 현재 코인 잔액", example = "190")
        int coinBalance,
        @Schema(description = "이번 호출에서 완료 보상(가구 또는 생성권)이 새로 지급됐는지", example = "false")
        boolean rewardGrantedNow,
        AttendanceEventStatusResponse status,
        @Schema(description = "이번 요청에서 캐릭터가 새로 지급됐는지. 기존 완료 보상 필드와 별개임")
        boolean characterGrantedNow) {
    public AttendanceCheckInResponse(boolean newCheckIn, int coinRewardAmount, int coinBalance,
            boolean rewardGrantedNow, AttendanceEventStatusResponse status) {
        this(newCheckIn, coinRewardAmount, coinBalance, rewardGrantedNow, status, false);
    }

}
