package com.triples.rougether.userapi.room.dto;

import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.entity.RoomItemPlacement;
import com.triples.rougether.domain.room.entity.RoomLayoutFormat;
import com.triples.rougether.domain.room.entity.RoomSurfaceSlot;
import com.triples.rougether.userapi.room.dto.RoomResponse.RoomCharacterResponse;
import com.triples.rougether.userapi.room.dto.RoomResponse.RoomPlacementResponse;
import com.triples.rougether.userapi.room.dto.RoomResponse.RoomSlotResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// 방 렌더링 부분집합 - 화면에 방을 그리는 데 필요한 것만(RoomResponse 하위 record 재사용).
// 집 미리보기(#177)처럼 비구성원에게 내려가는 자리에 쓰므로 활동 정보(streak)와
// 편집용 값(layoutRevision·updatedAt)은 의도적으로 뺀다.
public record RoomRenderResponse(
        @Schema(description = "방 성장 레벨 (첫 생성 시 0)", example = "1")
        int growthLevel,
        @Schema(description = "배치 데이터 정본. SLOT_V1 이면 slots, FREE_V1 이면 placements(+ surface 슬롯)가 정본",
                example = "FREE_V1")
        RoomLayoutFormat layoutFormat,
        @Schema(description = "착용 중인 캐릭터 (미착용이면 null)")
        RoomCharacterResponse character,
        @Schema(description = "슬롯별 현재 배치 목록 (아이템이 배치된 슬롯만 포함)")
        List<RoomSlotResponse> slots,
        @Schema(description = "자유배치 가구 목록 (zIndex 오름차순). FREE_V1 전환 전에는 빈 배열")
        List<RoomPlacementResponse> placements) {

    public static RoomRenderResponse of(PersonalRoom room, List<RoomSurfaceSlot> slots,
                                        List<RoomItemPlacement> placements,
                                        UserCharacter selectedCharacter) {
        return new RoomRenderResponse(
                room.getGrowthLevel(),
                room.getLayoutFormat(),
                RoomCharacterResponse.of(selectedCharacter),
                slots.stream().map(RoomSlotResponse::of).toList(),
                placements.stream().map(RoomPlacementResponse::of).toList());
    }
}
