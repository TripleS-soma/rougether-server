package com.triples.rougether.userapi.onboarding.dto;

import com.triples.rougether.domain.goal.entity.Goal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record GoalListResponse(List<GoalItem> items) {

    public record GoalItem(
            @Schema(description = "목표 ID — 온보딩 목표 저장(PUT /api/v1/onboarding/goals)의 goalIds와 공동집 생성(POST /api/v1/houses)의 goalId로 사용", example = "1") Long id,
            @Schema(description = "목표 코드", example = "wake_up") String code,
            @Schema(description = "목표 이름", example = "일찍 일어나기") String name,
            @Schema(description = "정렬 순서 — 목록은 이 값 오름차순으로 정렬됨", example = "0") int sortOrder) {

        public static GoalItem of(Goal goal) {
            return new GoalItem(goal.getId(), goal.getCode(), goal.getName(), goal.getSortOrder());
        }
    }

    public static GoalListResponse of(List<Goal> goals) {
        return new GoalListResponse(goals.stream().map(GoalItem::of).toList());
    }
}
