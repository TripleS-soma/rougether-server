package com.triples.rougether.userapi.routine.dto;

import com.triples.rougether.domain.routine.entity.Streak;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public record StreakSummaryResponse(
        @Schema(description = "현재 연속 일수", example = "3")
        int currentCount,
        @Schema(description = "최장 연속 일수", example = "10")
        int longestCount,
        @Schema(description = "마지막 성공 날짜(ISO-8601, 없으면 null)", example = "2026-06-29")
        LocalDate lastSuccessDate
) {

    public static StreakSummaryResponse from(Streak streak) {
        return new StreakSummaryResponse(
                streak.getCurrentCount(), streak.getLongestCount(), streak.getLastSuccessDate());
    }
}
