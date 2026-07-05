package com.triples.rougether.userapi.onboarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record OnboardingGoalsRequest(
        @Schema(description = "선택한 목표 ID 목록(최소 1개, 중복은 한 번만 저장) — GET /api/v1/goals 응답의 id(활성 목표만 허용)", example = "[1, 2, 3]") List<Long> goalIds,
        @Schema(description = "대표 목표 ID(선택, 생략 가능) — goalIds에 포함된 값 중 하나. 생략하면 대표 목표 없이 저장", example = "1") Long primaryGoalId) {
}
