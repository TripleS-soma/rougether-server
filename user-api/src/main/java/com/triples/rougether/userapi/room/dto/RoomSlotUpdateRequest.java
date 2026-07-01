package com.triples.rougether.userapi.room.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

// PUT /api/v1/rooms/me/slots 요청. 슬롯별 배치 지정.
// userItemId 가 null 이면 해당 슬롯을 비운다(배치 해제).
public record RoomSlotUpdateRequest(@NotNull @Valid List<SlotAssignment> slots) {

    public record SlotAssignment(@NotBlank String slotType, Long userItemId) {
    }
}
