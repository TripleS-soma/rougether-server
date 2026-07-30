package com.triples.rougether.userapi.character.dto;

import com.triples.rougether.domain.character.entity.UserCharacterAccessory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record CharacterAccessoriesResponse(
        @Schema(description = "보유 캐릭터 ID (user_characters)", example = "12")
        Long userCharacterId,
        @Schema(description = "적용 후 캐릭터의 전체 착용 악세사리 목록")
        List<EquippedAccessoryResponse> items) {

    public static CharacterAccessoriesResponse of(
            Long userCharacterId, List<UserCharacterAccessory> accessories) {
        return new CharacterAccessoriesResponse(
                userCharacterId,
                accessories.stream().map(EquippedAccessoryResponse::of).toList());
    }
}
