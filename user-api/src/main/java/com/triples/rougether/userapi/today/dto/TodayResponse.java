package com.triples.rougether.userapi.today.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

public record TodayResponse(
        @Schema(description = "조회 기준일(ISO-8601)", example = "2026-06-30")
        LocalDate date,
        @Schema(description = "카테고리별 묶음(미분류는 별도 그룹)")
        List<TodayCategoryGroup> categories,
        @Schema(description = "진행 요약")
        TodaySummary summary,
        @Schema(description = "스트릭 요약")
        TodayStreak streak
) {
}
