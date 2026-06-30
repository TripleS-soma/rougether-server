package com.triples.rougether.userapi.routine.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public record RoutineLogCreateRequest(
        @Schema(description = "완료 날짜(ISO-8601, 미지정 시 오늘 KST)", example = "2026-06-29")
        LocalDate routineDate
) {
}
