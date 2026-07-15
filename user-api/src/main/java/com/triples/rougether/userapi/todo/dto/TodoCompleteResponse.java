package com.triples.rougether.userapi.todo.dto;

import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.shared.CurrencyType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record TodoCompleteResponse(
        @Schema(description = "투두 ID", example = "1")
        Long id,
        @Schema(description = "투두 상태. 허용값: PENDING(대기), COMPLETED(완료). 완료 체크 직후에는 항상 COMPLETED", example = "COMPLETED")
        TodoStatus status,
        @Schema(description = "완료 시각(ISO-8601)", example = "2026-06-30T07:00:00Z")
        Instant completedAt,
        @Schema(description = "보상 재화 종류. 허용값: COIN(루틴 실천 보상), DIAMOND(아이템 구매)", example = "COIN")
        CurrencyType rewardCurrencyType,
        @Schema(description = "보상 금액. 마감일이 오늘인 완료는 코인 5이나, 일일 상한(4건) 도달 또는 마감일이 지난 완료는 0 지급", example = "5")
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
