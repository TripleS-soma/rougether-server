package com.triples.rougether.userapi.onboarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record OnboardingCharacterResponse(
        @Schema(description = "선택된 대표 캐릭터 ID", example = "1") Long selectedCharacterId) {
}
