package com.triples.rougether.userapi.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

public record AttendanceEventStatusResponse(
        Long eventId,
        String code,
        String title,
        LocalDate startsOn,
        LocalDate endsOn,
        int targetDays,
        int currentStreak,
        boolean checkedInToday,
        boolean completed,
        List<LocalDate> checkInDates,
        @Schema(description = "1일차부터 목표일까지의 보상표")
        List<DailyReward> dailyRewards,
        Reward reward) {

    public record DailyReward(
            int day,
            int coinAmount,
            boolean furnitureReward,
            boolean claimed) {
    }

    public record Reward(
            Long itemId,
            String name,
            String assetKey,
            Long userItemId,
            boolean received) {
    }
}
