package com.triples.rougether.userapi.room.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.room.dto.RoomResponse;
import com.triples.rougether.userapi.room.service.RoomQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 개인 방 조회. 인증된 사용자의 방 성장 현황 + 슬롯 배치 + 스트릭.
@RestController
@RequestMapping("/api/v1/rooms")
public class RoomController {

    private final RoomQueryService roomQueryService;

    public RoomController(RoomQueryService roomQueryService) {
        this.roomQueryService = roomQueryService;
    }

    @GetMapping("/me")
    public RoomResponse getMyRoom(@CurrentUser AuthUser user) {
        return roomQueryService.getMyRoom(user.id());
    }
}
