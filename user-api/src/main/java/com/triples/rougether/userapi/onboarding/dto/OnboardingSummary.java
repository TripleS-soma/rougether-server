package com.triples.rougether.userapi.onboarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record OnboardingSummary(
        @Schema(description = "온보딩 완료 여부(목표와 대표 캐릭터가 있거나 루틴/할 일 생성 이력이 있음)", example = "true")
        boolean completed,
        @Schema(description = "대표 목표 ID(없으면 null)", example = "1")
        Long primaryGoalId,
        @Schema(description = "선택된 대표 캐릭터 ID(없으면 null)", example = "1")
        Long selectedCharacterId) {
}
