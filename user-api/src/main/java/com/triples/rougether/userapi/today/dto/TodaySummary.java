package com.triples.rougether.userapi.today.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record TodaySummary(
        @Schema(description = "완료한 항목 수(기준일에 완료한 루틴 + COMPLETED 상태 투두)", example = "3")
        int completedCount,
        @Schema(description = "남은 항목 수(루틴+투두)", example = "2")
        int remainingCount,
        @Schema(description = "진행률(완료 수 / 전체 대상 수, 0.0~1.0. 대상이 없으면 0.0)", example = "0.6")
        double progressRate
) {
}
