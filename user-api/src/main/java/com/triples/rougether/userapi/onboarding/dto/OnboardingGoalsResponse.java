package com.triples.rougether.userapi.onboarding.dto;

import com.triples.rougether.domain.goal.entity.UserGoal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record OnboardingGoalsResponse(
        List<GoalSelection> goals,
        @Schema(description = "대표 목표 ID(없으면 null)", example = "1") Long primaryGoalId) {

    public record GoalSelection(
            @Schema(description = "목표 ID", example = "1") Long goalId,
            @Schema(description = "목표 코드", example = "wake_up") String code,
            @Schema(description = "목표 이름", example = "일찍 일어나기") String name) {

        public static GoalSelection of(UserGoal userGoal) {
            return new GoalSelection(
                    userGoal.getGoal().getId(),
                    userGoal.getGoal().getCode(),
                    userGoal.getGoal().getName());
        }
    }

    public static OnboardingGoalsResponse of(List<UserGoal> userGoals) {
        Long primaryGoalId = userGoals.stream()
                .filter(UserGoal::isPrimary)
                .map(ug -> ug.getGoal().getId())
                .findFirst()
                .orElse(null);
        return new OnboardingGoalsResponse(userGoals.stream().map(GoalSelection::of).toList(), primaryGoalId);
    }
}
