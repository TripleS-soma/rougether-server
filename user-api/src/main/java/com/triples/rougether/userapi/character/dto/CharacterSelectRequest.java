package com.triples.rougether.userapi.character.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record CharacterSelectRequest(
        @Schema(description = "착용할 캐릭터 ID. 내 보유 캐릭터 목록(GET /api/v1/me/characters) 응답의 characterId 값",
                example = "6")
        @NotNull Long characterId) {
}
