package com.triples.rougether.userapi.room.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

// PUT /api/v1/rooms/me/layout 요청. surface 슬롯 + 자유배치 가구를 단일 트랜잭션으로 저장(전체 교체).
// 값 범위·중복 검증은 기존 컨벤션대로 서비스에서 ErrorCode 로 통일한다.
public record RoomLayoutUpdateRequest(
        @Schema(description = "클라이언트가 마지막으로 읽은 layoutRevision. 서버 값과 다르면 409 "
                + "ROOM_LAYOUT_REVISION_CONFLICT (다른 기기의 저장 덮어쓰기 방지). 방 미생성 상태면 0", example = "4")
        @NotNull Integer baseRevision,
        @Schema(description = "surface 슬롯 배치(wallpaper/floor/background 만 허용). 포함된 slotType 만 갱신하는 "
                + "부분 갱신이며 userItemId 를 null 로 보내면 해당 슬롯을 비움. positioned 슬롯 타입은 거부됨")
        @NotNull @Valid List<SurfaceSlotAssignment> surfaceSlots,
        @Schema(description = "자유배치 가구 전체 목록 (전체 교체 — 요청에 없는 기존 배치는 삭제됨)")
        @NotNull @Valid List<PlacementItem> placements) {

    public record SurfaceSlotAssignment(
            @Schema(description = "surface 슬롯 타입. 허용값 3종: wallpaper(벽지)/floor(바닥)/background(배경)",
                    example = "wallpaper")
            @NotBlank String slotType,
            @Schema(description = "배치할 보유 아이템 ID. GET /api/v1/me/items (인벤토리) 응답의 userItemId 값. "
                    + "null 이면 해당 슬롯 비우기", example = "10")
            Long userItemId) {
    }

    public record PlacementItem(
            @Schema(description = "배치할 보유 아이템 ID. GET /api/v1/me/items (인벤토리) 응답의 userItemId 값. "
                    + "같은 값을 한 요청에 중복 지정할 수 없음(같은 가구 여러 개는 사본을 여러 개 소유)", example = "77")
            @NotNull Long userItemId,
            @Schema(description = "X 좌표 (방 렌더 영역 기준 0.0~1.0 정규화)", example = "0.32")
            @NotNull BigDecimal positionX,
            @Schema(description = "Y 좌표 (방 렌더 영역 기준 0.0~1.0 정규화)", example = "0.68")
            @NotNull BigDecimal positionY,
            @Schema(description = "쌓임 순서 (클수록 위)", example = "3")
            int zIndex,
            @Schema(description = "확대 배율 (0.1~5.0, 생략 시 1.0)", example = "1.1")
            BigDecimal scale,
            @Schema(description = "회전 각도 (-360~360, 생략 시 0)", example = "15")
            Integer rotationDeg,
            @Schema(description = "좌우 반전 여부 (생략 시 false)", example = "false")
            Boolean flipped) {
    }
}
