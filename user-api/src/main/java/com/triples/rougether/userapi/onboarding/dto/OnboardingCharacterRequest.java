package com.triples.rougether.userapi.onboarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record OnboardingCharacterRequest(
        @NotNull
        @Schema(description = "대표로 선택할 캐릭터 ID (GET /api/v1/characters 응답의 id, 활성 캐릭터만 허용) — 이미 캐릭터를 보유한 경우 보유한 캐릭터의 id만 사용", example = "1") Long characterId) {
}
