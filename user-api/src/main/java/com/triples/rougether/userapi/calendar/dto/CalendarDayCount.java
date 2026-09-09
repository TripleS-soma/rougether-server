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
        int todoCount,
        @Schema(description = "그날 표시 대상 루틴 중 완료 개수. routineCount 이하이며 일별 목록의 completed=true 개수와 같음",
                example = "2")
        int routineCompletedCount,
        @Schema(description = "그날 마감 투두 중 완료 개수. todoCount 이하이며 일별 목록의 COMPLETED 개수와 같음",
                example = "1")
        int todoCompletedCount
) {
}
