package com.triples.rougether.userapi.todo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record TodoUpdateRequest(
        @Schema(description = "투두 제목", example = "장보기")
        @Size(max = 160) String title,
        @Schema(description = "투두 설명", example = "우유, 계란 구매")
        @Size(max = 2000) String description,
        @Schema(description = "소속 카테고리 ID", example = "3")
        Long categoryId,
        @Schema(description = "마감일(ISO-8601)", example = "2026-07-01")
        LocalDate dueDate
) {
}
