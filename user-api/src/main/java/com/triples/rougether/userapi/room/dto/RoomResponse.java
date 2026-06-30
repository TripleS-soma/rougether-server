package com.triples.rougether.userapi.room.dto;

import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.entity.RoomSurfaceSlot;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.shop.entity.UserItem;
import java.time.Instant;
import java.util.List;

// GET /api/v1/rooms/me 응답: 방 성장 레벨 + 슬롯 배치 + 스트릭.
public record RoomResponse(
        Long roomUserId,
        int growthLevel,
        List<RoomSlotResponse> slots,
        RoomStreakResponse streak,
        Instant updatedAt) {

    public static RoomResponse of(PersonalRoom room, List<RoomSurfaceSlot> slots, Streak streak) {
        List<RoomSlotResponse> slotResponses = slots.stream()
                .map(RoomSlotResponse::of)
                .toList();
        return new RoomResponse(
                room.getUserId(),
                room.getGrowthLevel(),
                slotResponses,
                RoomStreakResponse.of(streak),
                room.getUpdatedAt());
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
