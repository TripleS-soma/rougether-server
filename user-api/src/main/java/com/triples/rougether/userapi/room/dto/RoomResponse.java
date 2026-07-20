package com.triples.rougether.userapi.room.dto;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.entity.RoomItemPlacement;
import com.triples.rougether.domain.room.entity.RoomLayoutFormat;
import com.triples.rougether.domain.room.entity.RoomSurfaceSlot;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.userapi.character.dto.CharacterAnimations;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

// GET /api/v1/rooms/me 응답: 방 성장 + 착용 캐릭터 + 슬롯 배치 + 자유배치 + 스트릭.
// layoutFormat/layoutRevision/placements 는 자유배치 도입으로 추가된 additive 필드 — 구버전 앱은 무시하고 slots 만 쓴다.
public record RoomResponse(
        @Schema(description = "방 소유자 회원 ID (방은 회원당 1개, userId 가 곧 방 식별자)", example = "1")
        Long roomUserId,
        @Schema(description = "방 성장 레벨 (첫 생성 시 0)", example = "1")
        int growthLevel,
        @Schema(description = "배치 데이터 정본. SLOT_V1 이면 slots, FREE_V1 이면 placements(+ surface 슬롯)가 정본. "
                + "SLOT_V1 방은 새 앱이 슬롯을 고정 좌표로 변환해 표시", example = "FREE_V1")
        RoomLayoutFormat layoutFormat,
        @Schema(description = "layout 저장 낙관적 잠금 값. PUT /rooms/me/layout 의 baseRevision 으로 그대로 보냄",
                example = "4")
        int layoutRevision,
        @Schema(description = "착용 중인 캐릭터 (미착용이면 null)")
        RoomCharacterResponse character,
        @Schema(description = "슬롯별 현재 배치 목록 (아이템이 배치된 슬롯만 포함, 비운 슬롯은 내려가지 않음)")
        List<RoomSlotResponse> slots,
        @Schema(description = "자유배치 가구 목록 (zIndex 오름차순). FREE_V1 전환 전에는 빈 배열")
        List<RoomPlacementResponse> placements,
        @Schema(description = "루틴 스트릭 (표시용)")
        RoomStreakResponse streak,
        @Schema(description = "방 최근 변경 시각")
        Instant updatedAt) {

    public static RoomResponse of(PersonalRoom room, List<RoomSurfaceSlot> slots,
                                  List<RoomItemPlacement> placements, Streak streak,
                                  UserCharacter selectedCharacter) {
        List<RoomSlotResponse> slotResponses = slots.stream()
                .map(RoomSlotResponse::of)
                .toList();
        List<RoomPlacementResponse> placementResponses = placements.stream()
                .map(RoomPlacementResponse::of)
                .toList();
        return new RoomResponse(
                room.getUserId(),
                room.getGrowthLevel(),
                room.getLayoutFormat(),
                room.getLayoutRevision(),
                RoomCharacterResponse.of(selectedCharacter),
                slotResponses,
                placementResponses,
                RoomStreakResponse.of(streak),
                room.getUpdatedAt());
    }

    // 착용(is_selected) 캐릭터. 없으면 null. 이미지는 assetKey(base_asset_key).
    public record RoomCharacterResponse(
            @Schema(description = "캐릭터 ID. GET /api/v1/characters (캐릭터 마스터 목록) 응답의 id 값", example = "1")
            Long characterId,
            @Schema(description = "캐릭터 코드", example = "cat")
            String code,
            @Schema(description = "캐릭터 이름", example = "고양이")
            String name,
            @Schema(description = "캐릭터 이미지 asset key (CDN base URL 과 조합해 사용)",
                    example = "characters/cat.png")
            String assetKey,
            @Schema(description = "애니메이션(APNG) asset key 묶음 (idle/poseCycle/wave)")
            CharacterAnimations animations) {
        public static RoomCharacterResponse of(UserCharacter userCharacter) {
            if (userCharacter == null) {
                return null;
            }
            Character character = userCharacter.getCharacter();
            return new RoomCharacterResponse(
                    character.getId(), character.getCode(), character.getName(), character.getBaseAssetKey(),
                    CharacterAnimations.of(character.getCode()));
        }
    }

    // 슬롯별 현재 배치. 빈 슬롯은 userItemId/assetKey 가 null.
    public record RoomSlotResponse(
            @Schema(description = "슬롯 타입 (surface: wallpaper/floor/background, positioned: topLeft/topCenter/"
                    + "topRight/midLeft/midRight/bottomLeft/bottomCenter/bottomRight)", example = "bottomCenter")
            String slotType,
            @Schema(description = "배치된 보유 아이템 ID. GET /api/v1/me/items (인벤토리) 응답의 userItemId 값 "
                    + "(빈 슬롯은 null)", example = "77")
            Long userItemId,
            @Schema(description = "배치된 아이템 이미지 asset key (CDN base URL 과 조합해 사용, 빈 슬롯은 null)",
                    example = "items/bakery-morning/furniture/bakery-morning-breakfast-table.png")
            String assetKey,
            @Schema(description = "배치 저장 시각")
            Instant savedAt) {
        public static RoomSlotResponse of(RoomSurfaceSlot slot) {
            UserItem userItem = slot.getUserItem();
            return new RoomSlotResponse(
                    slot.getSlotType(),
                    userItem != null ? userItem.getId() : null,
                    userItem != null ? userItem.getItem().getAssetKey() : null,
                    slot.getSavedAt());
        }
    }

    // 자유배치 가구 1개. 좌표는 방 렌더 영역 전체 기준 0.0~1.0 정규화. 이미지는 RoomSlotResponse 와 동일하게 assetKey.
    public record RoomPlacementResponse(
            @Schema(description = "배치된 보유 아이템 ID. GET /api/v1/me/items (인벤토리) 응답의 userItemId 값",
                    example = "77")
            Long userItemId,
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
            boolean flipped,
            @Schema(description = "배치 저장 시각")
            Instant updatedAt) {
        public static RoomPlacementResponse of(RoomItemPlacement placement) {
            UserItem userItem = placement.getUserItem();
            return new RoomPlacementResponse(
                    userItem.getId(),
                    userItem.getItem().getAssetKey(),
                    placement.getPositionX(),
                    placement.getPositionY(),
                    placement.getZIndex(),
                    placement.getScale(),
                    placement.getRotationDeg(),
                    placement.isFlipped(),
                    placement.getUpdatedAt());
        }
    }

    // 스트릭은 표시용 읽기(루틴 도메인 소유). 아직 없으면 0/0.
    public record RoomStreakResponse(
            @Schema(description = "현재 연속 실천 일수 (스트릭 미생성이면 0)", example = "3")
            int currentCount,
            @Schema(description = "최장 연속 실천 일수 (스트릭 미생성이면 0)", example = "14")
            int longestCount) {
        public static RoomStreakResponse of(Streak streak) {
            return streak != null
                    ? new RoomStreakResponse(streak.getCurrentCount(), streak.getLongestCount())
                    : new RoomStreakResponse(0, 0);
        }
    }
}
