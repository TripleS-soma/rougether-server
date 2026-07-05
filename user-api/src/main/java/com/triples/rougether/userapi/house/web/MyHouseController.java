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
            description = "로그인한 회원이 참여 중인 집 목록을 먼저 가입한 순서(가입 시각 오름차순)로 반환합니다. "
                    + "활성(ACTIVE)으로 참여 중인 집만 포함되며, 탈퇴·강퇴한 집과 정리(삭제)된 집은 제외됩니다. "
                    + "응답의 houseId 는 집 상세 조회 등 /api/v1/houses/{houseId} 경로에 사용합니다.")
    @GetMapping
    public MyHouseListResponse getMyHouses(@CurrentUser AuthUser user) {
        return houseQueryService.getMyHouses(user.id());
    }
}
