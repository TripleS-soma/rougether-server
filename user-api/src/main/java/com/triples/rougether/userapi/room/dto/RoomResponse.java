package com.triples.rougether.userapi.room.dto;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.entity.RoomSurfaceSlot;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.shop.entity.UserItem;
import java.time.Instant;
import java.util.List;

// GET /api/v1/rooms/me 응답: 방 성장 + 착용 캐릭터 + 슬롯 배치 + 스트릭.
public record RoomResponse(
        Long roomUserId,
        int growthLevel,
        RoomCharacterResponse character,
        List<RoomSlotResponse> slots,
        RoomStreakResponse streak,
        Instant updatedAt) {

    public static RoomResponse of(PersonalRoom room, List<RoomSurfaceSlot> slots, Streak streak,
                                  UserCharacter selectedCharacter) {
        List<RoomSlotResponse> slotResponses = slots.stream()
                .map(RoomSlotResponse::of)
                .toList();
        return new RoomResponse(
                room.getUserId(),
                room.getGrowthLevel(),
                RoomCharacterResponse.of(selectedCharacter),
                slotResponses,
                RoomStreakResponse.of(streak),
                room.getUpdatedAt());
    }

    // 착용(is_selected) 캐릭터. 없으면 null. 이미지는 assetKey(base_asset_key).
    public record RoomCharacterResponse(Long characterId, String code, String name, String assetKey) {
        public static RoomCharacterResponse of(UserCharacter userCharacter) {
            if (userCharacter == null) {
                return null;
            }
            Character character = userCharacter.getCharacter();
            return new RoomCharacterResponse(
                    character.getId(), character.getCode(), character.getName(), character.getBaseAssetKey());
        }
    }

    // 슬롯별 현재 배치. 빈 슬롯은 userItemId/assetKey 가 null.
    public record RoomSlotResponse(String slotType, Long userItemId, String assetKey, Instant savedAt) {
        public static RoomSlotResponse of(RoomSurfaceSlot slot) {
            UserItem userItem = slot.getUserItem();
            return new RoomSlotResponse(
                    slot.getSlotType(),
                    userItem != null ? userItem.getId() : null,
                    userItem != null ? userItem.getItem().getAssetKey() : null,
                    slot.getSavedAt());
        }
    }

    // 스트릭은 표시용 읽기(루틴 도메인 소유). 아직 없으면 0/0.
    public record RoomStreakResponse(int currentCount, int longestCount) {
        public static RoomStreakResponse of(Streak streak) {
            return streak != null
                    ? new RoomStreakResponse(streak.getCurrentCount(), streak.getLongestCount())
                    : new RoomStreakResponse(0, 0);
        }
    }
}
