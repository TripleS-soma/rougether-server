package com.triples.rougether.userapi.house.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.house.dto.HouseCreateRequest;
import com.triples.rougether.userapi.house.dto.HouseCreateResponse;
import com.triples.rougether.userapi.house.dto.HouseJoinByCodeRequest;
import com.triples.rougether.userapi.house.dto.HouseJoinDetailResponse;
import com.triples.rougether.userapi.house.dto.HouseJoinResponse;
import com.triples.rougether.userapi.house.dto.HousePreviewResponse;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 공동집 API. 생성자는 OWNER 로 즉시 등록되고 초대코드가 발급된다.
@Tag(name = "House", description = "공동집 관련 API")
@RestController
@RequestMapping("/api/v1/houses")
public class HouseController {

    private final HouseCommandService houseCommandService;
    private final HouseJoinService houseJoinService;

    public HouseController(HouseCommandService houseCommandService, HouseJoinService houseJoinService) {
        this.houseCommandService = houseCommandService;
        this.houseJoinService = houseJoinService;
    }

    @Operation(summary = "공동집 생성",
            description = "새 공동집을 만들고 생성자를 OWNER 구성원으로 등록하며 초대코드를 발급합니다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HouseCreateResponse create(@CurrentUser AuthUser user,
                                      @Valid @RequestBody HouseCreateRequest request) {
        return houseCommandService.create(user.id(), request);
    }

    @Operation(summary = "초대코드로 집 참여",
            description = "초대코드로 집에 즉시 가입합니다. 탈퇴 이력이 있으면 기존 구성원 정보를 재활성화합니다.")
    @PostMapping("/join-by-code")
    public HouseJoinResponse joinByCode(@CurrentUser AuthUser user,
                                        @Valid @RequestBody HouseJoinByCodeRequest request) {
        return houseJoinService.joinByCode(user.id(), request.inviteCode());
    }

    @Operation(summary = "집 참여",
            description = "탐색한 집에 즉시 가입합니다. 탈퇴 이력이 있으면 기존 구성원 정보를 재활성화합니다.")
    @PostMapping("/{houseId}/join")
    public HouseJoinDetailResponse join(@CurrentUser AuthUser user,
                                        @Parameter(description = "참여할 집 ID") @PathVariable Long houseId) {
        return houseJoinService.join(user.id(), houseId);
    }

    @Operation(summary = "초대코드로 집 미리보기",
            description = "참여 전에 초대코드로 집 정보와 정원, 코드 만료 여부를 확인합니다.")
    @GetMapping("/by-code/{inviteCode}")
    public HousePreviewResponse preview(@Parameter(description = "초대코드") @PathVariable String inviteCode) {
        return houseJoinService.preview(inviteCode);
    }
}
