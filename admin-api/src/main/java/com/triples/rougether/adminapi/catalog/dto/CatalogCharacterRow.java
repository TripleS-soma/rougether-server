package com.triples.rougether.adminapi.catalog.dto;

import com.triples.rougether.domain.character.entity.Character;

// 카탈로그 화면의 캐릭터 한 행.
public record CatalogCharacterRow(
        Long id,
        String code,
        String name,
        String baseAssetKey,
        int sortOrder,
        boolean active) {

    public static CatalogCharacterRow of(Character character) {
        return new CatalogCharacterRow(
                character.getId(),
                character.getCode(),
                character.getName(),
                character.getBaseAssetKey(),
                character.getSortOrder(),
                character.isActive());
    }
}
