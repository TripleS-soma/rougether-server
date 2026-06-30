package com.triples.rougether.userapi.todo.dto;

import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;

public record TodoResponse(
        @Schema(description = "투두 ID", example = "1")
        Long id,
        @Schema(description = "투두 제목", example = "장보기")
        String title,
        @Schema(description = "투두 설명", example = "우유, 계란 구매")
        String description,
        @Schema(description = "소속 카테고리 ID(미분류면 null)", example = "3")
        Long categoryId,
        @Schema(description = "마감일(ISO-8601)", example = "2026-07-01")
        LocalDate dueDate,
        @Schema(description = "투두 상태")
        TodoStatus status,
        @Schema(description = "완료 시각(ISO-8601, 미완료면 null)", example = "2026-06-30T07:00:00Z")
        Instant completedAt
) {

    public static TodoResponse from(Todo todo) {
        Category category = todo.getCategory();
        return new TodoResponse(
                todo.getId(),
                todo.getTitle(),
                todo.getDescription(),
                category != null ? category.getId() : null,
                todo.getDueDate(),
                todo.getStatus(),
                todo.getCompletedAt()
        );
    }
}
