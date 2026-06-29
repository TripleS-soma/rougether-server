package com.triples.rougether.userapi.routine.dto;

import com.triples.rougether.domain.routine.entity.AuthType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

public record RoutineUpdateRequest(
        @Schema(description = "루틴 제목", example = "아침 운동")
        @Size(max = 160) String title,
        @Schema(description = "소속 카테고리 ID(지정하면 해당 카테고리로 변경)", example = "3")
        Long categoryId,
        @Schema(description = "인증 방식")
        AuthType authType,
        @Schema(description = "반복 유형(DAILY/WEEKLY)", example = "WEEKLY")
        @Pattern(regexp = "DAILY|WEEKLY") String repeatType,
        @Schema(description = "반복 설정 JSON", example = "{\"daysOfWeek\":[\"MON\",\"WED\"]}")
        String repeatDays,
        @Schema(description = "수행 예정 시각(HH:mm:ss)", example = "07:00:00")
        LocalTime scheduledTime,
        @Schema(description = "시작일(ISO-8601)", example = "2026-07-01")
        LocalDate startsOn,
        @Schema(description = "종료일(ISO-8601)", example = "2026-12-31")
        LocalDate endsOn
) {
}
