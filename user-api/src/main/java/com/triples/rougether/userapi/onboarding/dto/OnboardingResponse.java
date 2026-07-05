package com.triples.rougether.userapi.onboarding.dto;

import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsResponse.GoalSelection;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record OnboardingResponse(
        List<GoalSelection> goals,
        @Schema(description = "대표 목표 ID(없으면 null)", example = "1")
        Long primaryGoalId,
        @Schema(description = "선택된 대표 캐릭터 ID(없으면 null)", example = "1")
        Long selectedCharacterId,
        @Schema(description = "온보딩 완료 여부(선택 목표 1개 이상 && 대표 캐릭터 존재)", example = "true")
        boolean completed) {
}
