package com.triples.rougether.userapi.onboarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record OnboardingGoalsRequest(
        @Schema(description = "선택한 목표 ID 목록(최소 1개)", example = "[1, 2, 3]") List<Long> goalIds,
        @Schema(description = "대표 목표 ID(선택, 생략 가능)", example = "1") Long primaryGoalId) {
}
