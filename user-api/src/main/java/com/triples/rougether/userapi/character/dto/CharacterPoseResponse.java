package com.triples.rougether.userapi.character.dto;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.CharacterPose;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Comparator;
import java.util.List;

public record CharacterPoseResponse(
        @Schema(description = "캐릭터 포즈 ID", example = "1") Long id,
        @Schema(description = "캐릭터 안에서 고유한 포즈 코드", example = "temp1") String code,
        @Schema(
                description = "포즈 에셋 key — CDN base URL과 조합해 사용",
                example = "characters/1f9c6b56-9c30-4554-b1ce-0be83642e627.webp")
        String assetKey,
        @Schema(description = "포즈 정렬 순서", example = "10") int sortOrder) {

    public static CharacterPoseResponse of(CharacterPose pose) {
        return new CharacterPoseResponse(
                pose.getId(),
                pose.getCode(),
                pose.getAssetKey(),
                pose.getSortOrder());
    }

    public static List<CharacterPoseResponse> activeOf(Character character) {
        return character.getPoses().stream()
                .filter(CharacterPose::isActive)
                .sorted(Comparator.comparingInt(CharacterPose::getSortOrder)
                        .thenComparing(CharacterPose::getId, Comparator.nullsLast(Long::compareTo)))
                .map(CharacterPoseResponse::of)
                .toList();
    }
}
