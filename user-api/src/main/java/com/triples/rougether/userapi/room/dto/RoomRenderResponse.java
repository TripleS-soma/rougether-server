package com.triples.rougether.userapi.room.dto;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.CharacterAccessoryRenderProfile;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.entity.UserCharacterAccessory;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.entity.RoomItemPlacement;
import com.triples.rougether.domain.room.entity.RoomLayoutFormat;
import com.triples.rougether.domain.room.entity.RoomSurfaceSlot;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.userapi.character.dto.AccessoryRenderProfileResponse;
import com.triples.rougether.userapi.character.dto.CharacterAnimations;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

// 방 렌더링 부분집합 - 화면에 방을 그리는 데 필요한 것만.
// 집 미리보기(#177)처럼 비구성원에게 내려가는 자리에 쓰므로 활동 정보(streak)·편집용 값
// (layoutRevision·updatedAt)에 더해 소유 리소스 식별자(userItemId)·배치 시각(savedAt)도
// RoomResponse 하위 record 를 재사용하지 않고 의도적으로 뺀다(렌더는 assetKey·좌표면 충분).
public record RoomRenderResponse(
        @Schema(description = "방 성장 레벨 (첫 생성 시 0)", example = "1")
        int growthLevel,
        @Schema(description = "배치 데이터 정본. SLOT_V1 이면 slots, FREE_V1 이면 placements(+ surface 슬롯)가 정본",
                example = "FREE_V1")
        RoomLayoutFormat layoutFormat,
        @Schema(description = "착용 중인 캐릭터 (미착용이면 null)")
        RenderCharacter character,
        @Schema(description = "슬롯별 현재 배치 목록 (아이템이 배치된 슬롯만 포함)")
        List<RenderSlot> slots,
        @Schema(description = "자유배치 가구 목록 (zIndex 오름차순). FREE_V1 전환 전에는 빈 배열")
        List<RenderPlacement> placements) {

    public static RoomRenderResponse of(PersonalRoom room, List<RoomSurfaceSlot> slots,
                                        List<RoomItemPlacement> placements,
                                        UserCharacter selectedCharacter,
                                        List<UserCharacterAccessory> accessories,
                                        Map<Long, Map<Long,
                                                List<CharacterAccessoryRenderProfile>>> renderProfiles) {
        return new RoomRenderResponse(
                room.getGrowthLevel(),
                room.getLayoutFormat(),
                RenderCharacter.of(selectedCharacter, accessories, renderProfiles),
                slots.stream().map(RenderSlot::of).toList(),
                placements.stream().map(RenderPlacement::of).toList());
    }

    // 공개 렌더의 캐릭터·악세사리는 합성에 필요한 카탈로그 정보만 제공한다.
    // userItemId·equippedAt 같은 소유 리소스 식별자/활동 시각은 노출하지 않는다.
    public record RenderCharacter(
            Long characterId,
            String code,
            String name,
            String assetKey,
            CharacterAnimations animations,
            List<RenderAccessory> accessories) {

        public static RenderCharacter of(
                UserCharacter userCharacter,
                List<UserCharacterAccessory> accessories,
                Map<Long, Map<Long,
                        List<CharacterAccessoryRenderProfile>>> renderProfiles) {
            if (userCharacter == null) {
                return null;
            }
            Character character = userCharacter.getCharacter();
            Map<Long, List<CharacterAccessoryRenderProfile>> byItem =
                    renderProfiles.getOrDefault(userCharacter.getId(), Map.of());
            return new RenderCharacter(
                    character.getId(),
                    character.getCode(),
                    character.getName(),
                    character.getBaseAssetKey(),
                    CharacterAnimations.of(character.getCode()),
                    accessories.stream()
                            .map(accessory -> RenderAccessory.of(
                                    accessory,
                                    byItem.getOrDefault(
                                            accessory.getUserItem().getItem().getId(), List.of())))
                            .toList());
        }
    }

    public record RenderAccessory(
            Long itemId,
            String name,
            String assetKey,
            String characterSlotType,
            List<AccessoryRenderProfileResponse> renderProfiles) {

        public static RenderAccessory of(
                UserCharacterAccessory accessory,
                List<CharacterAccessoryRenderProfile> renderProfiles) {
            UserItem userItem = accessory.getUserItem();
            return new RenderAccessory(
                    userItem.getItem().getId(),
                    userItem.getItem().getName(),
                    userItem.getItem().getAssetKey(),
                    accessory.getCharacterSlotType(),
                    renderProfiles.stream().map(AccessoryRenderProfileResponse::of).toList());
        }
    }

    // 슬롯 렌더 - RoomSlotResponse 에서 userItemId(소유 식별자)·savedAt(배치 시각)을 뺀 형태.
    public record RenderSlot(
            @Schema(description = "슬롯 타입 (surface: wallpaper/floor/background, positioned: topLeft 등)",
                    example = "wallpaper")
            String slotType,
            @Schema(description = "배치된 아이템 이미지 asset key (CDN base URL 과 조합해 사용, 빈 슬롯은 null)",
                    example = "items/bakery-morning/surface/bakery-morning-wallpaper.png")
            String assetKey) {
        public static RenderSlot of(RoomSurfaceSlot slot) {
            UserItem userItem = slot.getUserItem();
            return new RenderSlot(
                    slot.getSlotType(),
                    userItem != null ? userItem.getItem().getAssetKey() : null);
        }
    }

    // 자유배치 가구 렌더 - RoomPlacementResponse 에서 userItemId·updatedAt 을 뺀 형태. 좌표는 0.0~1.0 정규화.
    public record RenderPlacement(
            @Schema(description = "배치된 아이템 이미지 asset key (CDN base URL 과 조합해 사용)",
                    example = "items/bakery-morning/furniture/bakery-morning-breakfast-table.png")
            String assetKey,
            @Schema(description = "X 좌표 (0.0~1.0 정규화)", example = "0.32")
            BigDecimal positionX,
            @Schema(description = "Y 좌표 (0.0~1.0 정규화)", example = "0.68")
            BigDecimal positionY,
            @Schema(description = "쌓임 순서 (클수록 위)", example = "3")
            int zIndex,
            @Schema(description = "확대 배율", example = "1.1")
            BigDecimal scale,
            @Schema(description = "회전 각도 (-360~360)", example = "15")
            int rotationDeg,
            @Schema(description = "좌우 반전 여부", example = "false")
            boolean flipped) {
        public static RenderPlacement of(RoomItemPlacement placement) {
            return new RenderPlacement(
                    placement.getUserItem().getItem().getAssetKey(),
                    placement.getPositionX(),
                    placement.getPositionY(),
                    placement.getZIndex(),
                    placement.getScale(),
                    placement.getRotationDeg(),
                    placement.isFlipped());
        }
    }
}
