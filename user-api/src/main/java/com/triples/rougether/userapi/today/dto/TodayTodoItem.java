package com.triples.rougether.userapi.today.dto;

import com.triples.rougether.domain.routine.entity.TodoStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;

public record TodayTodoItem(
        @Schema(description = "투두 ID", example = "1")
        Long id,
        @Schema(description = "투두 제목", example = "장보기")
        String title,
        @Schema(description = "마감일(ISO-8601)", example = "2026-06-30")
        LocalDate dueDate,
        @Schema(description = "투두 상태")
        TodoStatus status,
        @Schema(description = "완료 시각(ISO-8601, 미완료면 null)", example = "2026-06-30T07:00:00Z")
        Instant completedAt
) {
}
