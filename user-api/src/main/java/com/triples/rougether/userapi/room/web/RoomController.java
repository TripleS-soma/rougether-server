package com.triples.rougether.userapi.room.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.room.dto.RoomResponse;
import com.triples.rougether.userapi.room.dto.RoomSlotUpdateRequest;
import com.triples.rougether.userapi.room.service.RoomCommandService;
import com.triples.rougether.userapi.room.service.RoomQueryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 개인 방 조회/배치. 인증된 사용자의 방 성장 현황 + 슬롯 배치 + 스트릭.
@RestController
@RequestMapping("/api/v1/rooms")
public class RoomController {

    private final RoomQueryService roomQueryService;
    private final RoomCommandService roomCommandService;

    public RoomController(RoomQueryService roomQueryService, RoomCommandService roomCommandService) {
        this.roomQueryService = roomQueryService;
        this.roomCommandService = roomCommandService;
    }

    @GetMapping("/me")
    public RoomResponse getMyRoom(@CurrentUser AuthUser user) {
        return roomQueryService.getMyRoom(user.id());
    }

    // 슬롯 배치 저장(surface 3 + positioned 8). userItemId null 이면 해당 슬롯 비우기.
    @PutMapping("/me/slots")
    public RoomResponse updateSlots(@CurrentUser AuthUser user,
                                    @Valid @RequestBody RoomSlotUpdateRequest request) {
        return roomCommandService.updateSlots(user.id(), request);
    }
}
