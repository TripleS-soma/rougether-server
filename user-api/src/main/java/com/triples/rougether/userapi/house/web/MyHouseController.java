package com.triples.rougether.userapi.house.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.house.dto.HouseOrderUpdateRequest;
import com.triples.rougether.userapi.house.dto.MyHouseListResponse;
import com.triples.rougether.userapi.house.service.HouseOrderService;
import com.triples.rougether.userapi.house.service.HouseQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 내 집 목록. 집 탭에서 내가 속한 집들을 스와이프하는 화면용.
@Tag(name = "House", description = "공동집 관련 API")
@RestController
@RequestMapping("/api/v1/me/houses")
public class MyHouseController {

    private final HouseQueryService houseQueryService;
    private final HouseOrderService houseOrderService;

    public MyHouseController(HouseQueryService houseQueryService, HouseOrderService houseOrderService) {
        this.houseQueryService = houseQueryService;
        this.houseOrderService = houseOrderService;
    }

    @Operation(summary = "내 집 목록 조회",
            description = "로그인한 회원이 참여 중인 집 목록을 사용자가 저장한 노출 순서로 반환합니다. "
                    + "순서를 저장하지 않은 집과 새로 가입한 집은 마지막에 가입 시각 오름차순으로 배치됩니다. "
                    + "활성(ACTIVE)으로 참여 중인 집만 포함되며, 탈퇴·강퇴한 집과 정리(삭제)된 집은 제외됩니다. "
                    + "응답의 houseId 는 집 상세 조회 등 /api/v1/houses/{houseId} 경로에 사용합니다.")
    @GetMapping
    public MyHouseListResponse getMyHouses(@CurrentUser AuthUser user) {
        return houseQueryService.getMyHouses(user.id());
    }

    @Operation(summary = "내 집 순서 변경",
            description = "집 탭에 노출할 순서를 저장합니다. 현재 참여 중인 미삭제 집 ID 전체를 중복 없이 보내야 하며, "
                    + "목록의 첫 번째 집이 탭 최초 진입 시 먼저 노출됩니다.")
    @PutMapping("/order")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorderMyHouses(@CurrentUser AuthUser user,
                                @Valid @RequestBody HouseOrderUpdateRequest request) {
        houseOrderService.reorder(user.id(), request.houseIds());
    }
}
