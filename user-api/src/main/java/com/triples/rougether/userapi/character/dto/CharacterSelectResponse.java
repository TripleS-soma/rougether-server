package com.triples.rougether.userapi.character.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record CharacterSelectResponse(
        @Schema(description = "교체 후 착용 중인 캐릭터 ID", example = "6")
        Long selectedCharacterId) {
}
