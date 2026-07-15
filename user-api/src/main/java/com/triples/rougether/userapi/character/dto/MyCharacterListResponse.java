package com.triples.rougether.userapi.character.dto;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

// GET /api/v1/me/characters 응답. 보유 캐릭터 목록 - 마스터 정렬(sortOrder) 순.
public record MyCharacterListResponse(List<MyCharacterItem> items) {

    public record MyCharacterItem(
            @Schema(description = "보유 캐릭터 ID (user_characters)", example = "12")
            Long userCharacterId,
            @Schema(description = "캐릭터 ID. GET /api/v1/characters (마스터 목록) 응답의 id 와 동일. "
                    + "착용 교체(PUT /api/v1/onboarding/character)의 characterId 로 사용", example = "6")
            Long characterId,
            @Schema(description = "캐릭터 코드", example = "panda")
            String code,
            @Schema(description = "캐릭터 이름", example = "Panda")
            String name,
            @Schema(description = "기본 에셋 key (CDN base URL 과 조합해 사용)",
                    example = "characters/panda_sitting_figma_ready_v2.png")
            String baseAssetKey,
            @Schema(description = "애니메이션(APNG) asset key 묶음 (idle/poseCycle/wave)")
            CharacterAnimations animations,
            @Schema(description = "착용 여부 (동시에 1개만 true). 방 화면의 캐릭터가 이 항목", example = "true")
            boolean selected,
            @Schema(description = "획득 시각 (온보딩 무료 선택 또는 뽑기 지급)")
            Instant acquiredAt) {

        public static MyCharacterItem of(UserCharacter userCharacter) {
            Character character = userCharacter.getCharacter();
            return new MyCharacterItem(
                    userCharacter.getId(),
                    character.getId(),
                    character.getCode(),
                    character.getName(),
                    character.getBaseAssetKey(),
                    CharacterAnimations.of(character.getCode()),
                    userCharacter.isSelected(),
                    userCharacter.getAcquiredAt());
        }
    }

    public static MyCharacterListResponse of(List<UserCharacter> owned) {
        return new MyCharacterListResponse(owned.stream().map(MyCharacterItem::of).toList());
    }
}
