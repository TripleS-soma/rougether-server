package com.triples.rougether.adminapi.characterpose.dto;

import com.triples.rougether.domain.character.entity.CharacterPose;

public record CharacterPoseAdminResponse(
        Long id,
        Long characterId,
        String characterCode,
        String characterName,
        String code,
        String assetKey,
        int sortOrder,
        boolean active) {

    public static CharacterPoseAdminResponse of(CharacterPose pose) {
        return new CharacterPoseAdminResponse(
                pose.getId(),
                pose.getCharacter().getId(),
                pose.getCharacter().getCode(),
                pose.getCharacter().getName(),
                pose.getCode(),
                pose.getAssetKey(),
                pose.getSortOrder(),
                pose.isActive());
    }
}
