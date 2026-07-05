package com.triples.rougether.userapi.onboarding.dto;

import com.triples.rougether.domain.goal.entity.Goal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record GoalListResponse(List<GoalItem> items) {

    public record GoalItem(
            @Schema(description = "목표 ID", example = "1") Long id,
            @Schema(description = "목표 코드", example = "wake_up") String code,
            @Schema(description = "목표 이름", example = "일찍 일어나기") String name,
            @Schema(description = "정렬 순서", example = "0") int sortOrder) {

        public static GoalItem of(Goal goal) {
            return new GoalItem(goal.getId(), goal.getCode(), goal.getName(), goal.getSortOrder());
        }
    }

    public static GoalListResponse of(List<Goal> goals) {
        return new GoalListResponse(goals.stream().map(GoalItem::of).toList());
    }
}
