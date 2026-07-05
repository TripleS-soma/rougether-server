package com.triples.rougether.userapi.today.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

public record TodayResponse(
        @Schema(description = "조회 기준일(YYYY-MM-DD). date 파라미터 미지정 시 오늘(KST)", example = "2026-06-30")
        LocalDate date,
        @Schema(description = "카테고리별 묶음. categoryId 오름차순 정렬, 미분류(categoryId=null) 그룹은 맨 뒤")
        List<TodayCategoryGroup> categories,
        @Schema(description = "진행 요약(포함된 루틴·투두 기준 완료/남은 개수·진행률)")
        TodaySummary summary,
        @Schema(description = "스트릭 요약. 루틴 완료로만 쌓이며, 완료 이력이 없으면 0/0/null")
        TodayStreak streak
) {
}
