package com.triples.rougether.userapi.calendar.dto;

import com.triples.rougether.userapi.today.dto.TodayCategoryGroup;
import com.triples.rougether.userapi.today.dto.TodaySummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

public record CalendarDayResponse(
        @Schema(description = "조회 기준일(YYYY-MM-DD)", example = "2026-06-30")
        LocalDate date,
        @Schema(description = "카테고리별 묶음. categoryId 오름차순 정렬, 미분류(categoryId=null) 그룹은 맨 뒤")
        List<TodayCategoryGroup> categories,
        @Schema(description = "진행 요약(그날 대상 루틴·투두 기준 완료/남은 개수·진행률)")
        TodaySummary summary
) {
}
