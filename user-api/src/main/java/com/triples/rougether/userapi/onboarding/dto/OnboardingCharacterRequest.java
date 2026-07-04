package com.triples.rougether.userapi.onboarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record OnboardingCharacterRequest(
        @NotNull
        @Schema(description = "대표로 선택할 캐릭터 ID", example = "1") Long characterId) {
}
