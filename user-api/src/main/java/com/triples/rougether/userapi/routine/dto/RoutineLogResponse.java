package com.triples.rougether.userapi.routine.dto;

import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.shared.CurrencyType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;

public record RoutineLogResponse(
        @Schema(description = "완료 기록 ID", example = "1")
        Long id,
        @Schema(description = "완료 날짜(ISO-8601)", example = "2026-06-29")
        LocalDate routineDate,
        @Schema(description = "완료 상태")
        RoutineLogStatus status,
        @Schema(description = "완료 시각(ISO-8601)", example = "2026-06-29T07:00:00Z")
        Instant completedAt,
        @Schema(description = "보상 재화 종류")
        CurrencyType rewardCurrencyType,
        @Schema(description = "보상 금액", example = "10")
        int rewardAmount,
        @Schema(description = "갱신된 스트릭 요약")
        StreakSummaryResponse streak
) {

    public static RoutineLogResponse from(RoutineLog log, Streak streak) {
        return new RoutineLogResponse(
                log.getId(),
                log.getRoutineDate(),
                log.getStatus(),
                log.getCompletedAt(),
                log.getRewardCurrencyType(),
                log.getRewardAmount(),
                StreakSummaryResponse.from(streak)
        );
    }
}
