package com.triples.rougether.userapi.todo.dto;

import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.shared.CurrencyType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record TodoCompleteResponse(
        @Schema(description = "투두 ID", example = "1")
        Long id,
        @Schema(description = "투두 상태")
        TodoStatus status,
        @Schema(description = "완료 시각(ISO-8601)", example = "2026-06-30T07:00:00Z")
        Instant completedAt,
        @Schema(description = "보상 재화 종류")
        CurrencyType rewardCurrencyType,
        @Schema(description = "보상 금액", example = "5")
        int rewardAmount
) {

    public static TodoCompleteResponse from(Todo todo) {
        return new TodoCompleteResponse(
                todo.getId(),
                todo.getStatus(),
                todo.getCompletedAt(),
                todo.getRewardCurrencyType(),
                todo.getRewardAmount()
        );
    }
}
