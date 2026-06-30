package com.triples.rougether.userapi.todo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record TodoListResponse(
        @Schema(description = "투두 목록")
        List<TodoResponse> items
) {
}
