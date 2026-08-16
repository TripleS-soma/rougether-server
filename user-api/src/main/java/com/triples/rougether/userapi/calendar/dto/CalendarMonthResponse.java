package com.triples.rougether.userapi.calendar.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.YearMonth;
import java.util.List;

public record CalendarMonthResponse(
        @Schema(description = "조회 월(YYYY-MM)", example = "2026-08", type = "string")
        YearMonth yearMonth,
        @Schema(description = "그 달의 모든 날짜(1일~말일) 순서대로. 대상이 없는 날도 0으로 포함")
        List<CalendarDayCount> days
) {
}
