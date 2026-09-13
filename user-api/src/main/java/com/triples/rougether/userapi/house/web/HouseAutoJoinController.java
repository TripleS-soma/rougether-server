package com.triples.rougether.userapi.house.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.house.dto.HouseAutoJoinRequest;
import com.triples.rougether.userapi.house.dto.HouseAutoJoinResponse;
import com.triples.rougether.userapi.house.service.HouseAutoJoinService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "House")
@RestController
@RequestMapping("/api/v1/houses/{houseId}/auto-join")
@RequiredArgsConstructor
public class HouseAutoJoinController {
    private final HouseAutoJoinService service;

    @Operation(summary = "온보딩 자동 입주 설정 조회", description = "집 소유자 전용입니다.")
    @GetMapping
    public HouseAutoJoinResponse get(@CurrentUser AuthUser user, @PathVariable Long houseId) {
        return service.get(user.id(), houseId);
    }

    @Operation(summary = "온보딩 자동 입주 설정 변경", description = "집 소유자 전용입니다. enabled=true이면 공개 상태에서 "
            + "온보딩 신규 사용자가 방장 승인 없이 입주할 수 있습니다. 비공개 집은 자동 입주 대상에서 제외됩니다.")
    @PutMapping
    public HouseAutoJoinResponse update(@CurrentUser AuthUser user, @PathVariable Long houseId,
                                        @Valid @RequestBody HouseAutoJoinRequest request) {
        return service.update(user.id(), houseId, request.enabled());
    }
}
