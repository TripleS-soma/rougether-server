package com.triples.rougether.userapi.room.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

// PUT /api/v1/rooms/me/slots 요청. 슬롯별 배치 지정.
// userItemId 가 null 이면 해당 슬롯을 비운다(배치 해제).
// 목록의 null 원소는 validation 에서 400 으로 거부한다(서비스 NPE→500 방지).
public record RoomSlotUpdateRequest(@NotNull @Valid List<@NotNull SlotAssignment> slots) {

    public record SlotAssignment(
            @Schema(description = "슬롯 타입. 허용값 11종 — surface 3종: wallpaper(벽지)/floor(바닥)/background(배경), "
                    + "positioned 8종: topLeft/topCenter/topRight/midLeft/midRight/bottomLeft/bottomCenter/bottomRight"
                    + "(방 중앙 midCenter 는 캐릭터 자리라 없음). 한 요청에 같은 값을 중복 지정하지 않음",
                    example = "bottomCenter")
            @NotBlank String slotType,
            @Schema(description = "배치할 보유 아이템 ID. GET /api/v1/me/items (인벤토리) 응답의 userItemId 값 "
                    + "(본인이 보유한 아이템만 지정 가능). null 이면 해당 슬롯 비우기(기존 배치 삭제)", example = "77")
            Long userItemId) {
    }
}
