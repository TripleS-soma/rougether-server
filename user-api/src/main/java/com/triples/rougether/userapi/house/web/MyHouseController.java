package com.triples.rougether.userapi.house.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.house.dto.MyHouseListResponse;
import com.triples.rougether.userapi.house.service.HouseQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 내 집 목록. 집 탭에서 내가 속한 집들을 스와이프하는 화면용.
@Tag(name = "House", description = "공동집 관련 API")
@RestController
@RequestMapping("/api/v1/me/houses")
public class MyHouseController {

    private final HouseQueryService houseQueryService;

    public MyHouseController(HouseQueryService houseQueryService) {
        this.houseQueryService = houseQueryService;
    }

    @Operation(summary = "내 집 목록 조회",
            description = "로그인한 회원이 참여 중인 집 목록을 먼저 가입한 순서로 반환합니다.")
    @GetMapping
    public MyHouseListResponse getMyHouses(@CurrentUser AuthUser user) {
        return houseQueryService.getMyHouses(user.id());
    }
}
