package com.triples.rougether.userapi.routine.dto;

import com.triples.rougether.domain.routine.entity.Streak;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public record StreakSummaryResponse(
        @Schema(description = "현재 연속 일수. 하루에 루틴을 1개 이상 완료하면 그날이 성공일로 집계되며, "
                + "마지막 성공일이 어제보다 이전이면 0", example = "3")
        int currentCount,
        @Schema(description = "최장 연속 일수. 완료 취소로 스트릭이 롤백되어도 줄어들지 않음", example = "10")
        int longestCount,
        @Schema(description = "마지막 성공 날짜(YYYY-MM-DD, 없으면 null)", example = "2026-06-29")
        LocalDate lastSuccessDate
) {

    public static StreakSummaryResponse from(Streak streak, LocalDate referenceDate) {
        return new StreakSummaryResponse(
                streak.currentCountOn(referenceDate), streak.getLongestCount(), streak.getLastSuccessDate());
    }
}
