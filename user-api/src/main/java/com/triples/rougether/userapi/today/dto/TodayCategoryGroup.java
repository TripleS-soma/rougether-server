package com.triples.rougether.userapi.today.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record TodayCategoryGroup(
        @Schema(description = "카테고리 ID(미분류면 null)", example = "3")
        Long categoryId,
        @Schema(description = "카테고리 이름(미분류면 null)", example = "운동")
        String name,
        @Schema(description = "오늘 대상 루틴 목록")
        List<TodayRoutineItem> routines,
        @Schema(description = "오늘 대상 투두 목록")
        List<TodayTodoItem> todos
) {
}
