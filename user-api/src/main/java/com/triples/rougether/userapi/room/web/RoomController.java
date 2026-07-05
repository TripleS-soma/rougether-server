package com.triples.rougether.userapi.room.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.room.dto.RoomResponse;
import com.triples.rougether.userapi.room.dto.RoomSlotUpdateRequest;
import com.triples.rougether.userapi.room.service.RoomCommandService;
import com.triples.rougether.userapi.room.service.RoomQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 개인 방 조회/배치. 인증된 사용자의 방 성장 현황 + 슬롯 배치 + 스트릭.
@Tag(name = "Room", description = "방 관련 API")
@RestController
@RequestMapping("/api/v1/rooms")
public class RoomController {

    private final RoomQueryService roomQueryService;
    private final RoomCommandService roomCommandService;

    public RoomController(RoomQueryService roomQueryService, RoomCommandService roomCommandService) {
        this.roomQueryService = roomQueryService;
        this.roomCommandService = roomCommandService;
    }

    @Operation(summary = "내 방 조회",
            description = "로그인한 회원의 방 성장 현황, 착용 캐릭터, 슬롯별 배치, 스트릭을 반환합니다. "
                    + "첫 조회 시 방이 자동 생성되며 growthLevel 0 부터 시작합니다. "
                    + "slots 에는 아이템이 배치된 슬롯만 포함되고, 캐릭터 미착용이면 character 는 null 입니다. "
                    + "응답의 asset key 는 CDN base URL 과 조합해 이미지 URL 로 사용합니다.")
    @GetMapping("/me")
    public RoomResponse getMyRoom(@CurrentUser AuthUser user) {
        return roomQueryService.getMyRoom(user.id());
    }

    // 슬롯 배치 저장(surface 3 + positioned 8). userItemId null 이면 해당 슬롯 비우기.
    @Operation(summary = "내 방 슬롯 배치 저장",
            description = "슬롯별 아이템 배치를 저장하고 저장 후의 방 전체 상태(내 방 조회와 동일 형식)를 반환합니다. "
                    + "요청에 포함된 slotType 만 갱신하는 부분 갱신 방식이라, 요청에 없는 슬롯의 기존 배치는 그대로 유지됩니다. "
                    + "같은 slotType 은 한 요청에 한 번만 지정할 수 있습니다. "
                    + "슬롯은 surface 3종(wallpaper/floor/background)과 positioned 8종(topLeft/topCenter/topRight/"
                    + "midLeft/midRight/bottomLeft/bottomCenter/bottomRight) 총 11종이며, 방 중앙(midCenter)은 캐릭터 자리라 슬롯이 없습니다. "
                    + "userItemId 는 GET /api/v1/me/items (인벤토리) 응답의 userItemId 값을 사용하며, 보유한 아이템만 배치할 수 있습니다. "
                    + "userItemId 를 null 로 보내면 해당 슬롯을 비웁니다(기존 배치 삭제). "
                    + "슬롯 종류와 아이템 종류(placementType·surfaceSlotType)의 매칭은 서버가 강제하지 않으므로 클라이언트가 맞춰 배치합니다. "
                    + "방이 아직 없으면 자동 생성 후 저장합니다.")
    @PutMapping("/me/slots")
    public RoomResponse updateSlots(@CurrentUser AuthUser user,
                                    @Valid @RequestBody RoomSlotUpdateRequest request) {
        return roomCommandService.updateSlots(user.id(), request);
    }
}
