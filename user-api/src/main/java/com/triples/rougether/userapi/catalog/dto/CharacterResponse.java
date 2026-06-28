package com.triples.rougether.userapi.catalog.dto;

import com.triples.rougether.domain.character.entity.Character;

public record CharacterResponse(
        Long id,
        String code,
        String name,
        String baseAssetKey,
        int sortOrder) {

    public static CharacterResponse from(Character character) {
        return new CharacterResponse(
                character.getId(),
                character.getCode(),
                character.getName(),
                character.getBaseAssetKey(),
                character.getSortOrder());
    }
}
