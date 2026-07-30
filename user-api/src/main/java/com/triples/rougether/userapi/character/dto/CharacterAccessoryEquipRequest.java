package com.triples.rougether.userapi.character.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record CharacterAccessoryEquipRequest(
        @Schema(description = "적용할 보유 아이템 ID (GET /api/v1/me/items의 userItemId)", example = "77")
        @NotNull Long userItemId) {
}
