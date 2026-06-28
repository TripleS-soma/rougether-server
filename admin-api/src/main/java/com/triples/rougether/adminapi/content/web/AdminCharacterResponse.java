package com.triples.rougether.adminapi.content.web;

import com.triples.rougether.domain.character.entity.Character;

public record AdminCharacterResponse(
        Long id,
        String code,
        String name,
        String baseAssetKey,
        int sortOrder,
        boolean active) {

    public static AdminCharacterResponse from(Character character) {
        return new AdminCharacterResponse(
                character.getId(),
                character.getCode(),
                character.getName(),
                character.getBaseAssetKey(),
                character.getSortOrder(),
                character.isActive());
    }
}
