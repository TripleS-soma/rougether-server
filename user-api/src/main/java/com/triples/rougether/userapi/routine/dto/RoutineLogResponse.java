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
        @Schema(description = "완료 날짜(YYYY-MM-DD)", example = "2026-06-29")
        LocalDate routineDate,
        @Schema(description = "완료 상태. 허용값: PENDING(미수행), COMPLETED(완료), FAILED(실패)", example = "COMPLETED")
        RoutineLogStatus status,
        @Schema(description = "완료 시각(ISO-8601)", example = "2026-06-29T07:00:00Z")
        Instant completedAt,
        @Schema(description = "보상 재화 종류. 허용값: COIN(루틴 실천 보상), DIAMOND(아이템 구매)", example = "COIN")
        CurrencyType rewardCurrencyType,
        @Schema(description = "보상 금액. 오늘 완료는 코인 10이나, 일일 상한(4건) 도달 또는 과거 날짜 완료는 0 지급", example = "10")
        int rewardAmount,
        @Schema(description = "스트릭 요약. 오늘 첫 완료면 갱신된 값, 그 외(오늘 추가 완료·과거 날짜 완료)는 기존 값 그대로")
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
                // 과거 완료는 스트릭을 건드리지 않으므로 스트릭이 아직 없을 수 있음
                streak != null ? StreakSummaryResponse.from(streak) : new StreakSummaryResponse(0, 0, null)
        );
    }
}
