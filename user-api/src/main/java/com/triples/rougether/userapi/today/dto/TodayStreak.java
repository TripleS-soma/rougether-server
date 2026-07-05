package com.triples.rougether.userapi.today.dto;

import com.triples.rougether.domain.routine.entity.Streak;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public record TodayStreak(
        @Schema(description = "현재 연속일. 하루에 루틴을 1개 이상 완료하면 그날이 성공일로 집계됨. 스트릭이 없으면 0", example = "5")
        int currentCount,
        @Schema(description = "최장 연속일. 완료 취소로 롤백되어도 줄어들지 않음", example = "12")
        int longestCount,
        @Schema(description = "마지막 성공일(YYYY-MM-DD, 없으면 null)", example = "2026-06-30")
        LocalDate lastSuccessDate
) {

    public static TodayStreak from(Streak streak) {
        if (streak == null) {
            return new TodayStreak(0, 0, null);
        }
        return new TodayStreak(streak.getCurrentCount(), streak.getLongestCount(),
                streak.getLastSuccessDate());
    }
}
