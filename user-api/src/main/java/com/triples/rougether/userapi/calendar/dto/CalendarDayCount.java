package com.triples.rougether.userapi.calendar.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public record CalendarDayCount(
        @Schema(description = "날짜(YYYY-MM-DD)", example = "2026-08-16")
        LocalDate date,
        @Schema(description = "그날 대상 루틴 개수(완료·미완료 합산). 소싱 규칙은 GET /api/v1/calendar와 동일",
                example = "3")
        int routineCount,
        @Schema(description = "그날 마감(dueDate) 투두 개수(완료·미완료 합산)", example = "1")
        int todoCount
) {
}
