package com.triples.rougether.userapi.today.dto;

import com.triples.rougether.domain.routine.entity.TodoStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record TodayTodoItem(
        @Schema(description = "투두 ID. 투두 완료 체크/취소 등 투두 API 경로의 {id}로 사용", example = "1")
        Long id,
        @Schema(description = "투두 제목", example = "장보기")
        String title,
        @Schema(description = "마감일(YYYY-MM-DD)", example = "2026-06-30")
        LocalDate dueDate,
        @Schema(description = "마감 시각(HH:mm:ss, 미지정이면 null)", example = "18:00:00")
        LocalTime dueTime,
        @Schema(description = "투두 상태. 허용값: PENDING(대기), COMPLETED(완료)", example = "PENDING")
        TodoStatus status,
        @Schema(description = "완료 시각(ISO-8601, 미완료면 null)", example = "2026-06-30T07:00:00Z")
        Instant completedAt
) {
}
