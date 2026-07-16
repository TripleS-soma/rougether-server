package com.triples.rougether.userapi.onboarding.dto;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.userapi.character.dto.CharacterAnimations;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record CharacterListResponse(List<CharacterItem> items) {

    public record CharacterItem(
            @Schema(description = "캐릭터 ID — 대표 캐릭터 선택(PUT /api/v1/onboarding/character)의 characterId로 사용", example = "1") Long id,
            @Schema(description = "캐릭터 코드", example = "cat") String code,
            @Schema(description = "캐릭터 이름", example = "고양이") String name,
            @Schema(description = "기본 에셋 key — CDN base URL과 조합해 이미지 URL로 사용", example = "characters/cat.png") String baseAssetKey,
            @Schema(description = "애니메이션(APNG) asset key 묶음 (idle/poseCycle/wave)") CharacterAnimations animations,
            @Schema(description = "정렬 순서 — 목록은 이 값 오름차순으로 정렬됨", example = "0") int sortOrder) {

        public static CharacterItem of(Character character) {
            return new CharacterItem(
                    character.getId(),
                    character.getCode(),
                    character.getName(),
                    character.getBaseAssetKey(),
                    CharacterAnimations.of(character.getCode()),
                    character.getSortOrder());
        }
    }

    public static CharacterListResponse of(List<Character> characters) {
        return new CharacterListResponse(characters.stream().map(CharacterItem::of).toList());
    }
}
