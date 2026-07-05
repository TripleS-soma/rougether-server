package com.triples.rougether.userapi.house.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.house.dto.HouseCreateRequest;
import com.triples.rougether.userapi.house.dto.HouseCreateResponse;
import com.triples.rougether.userapi.house.dto.HouseDetailResponse;
import com.triples.rougether.userapi.house.dto.HouseJoinByCodeRequest;
import com.triples.rougether.userapi.house.dto.HouseListResponse;
import com.triples.rougether.userapi.house.dto.HouseMemberListResponse;
import com.triples.rougether.userapi.house.dto.HouseJoinDetailResponse;
import com.triples.rougether.userapi.house.dto.HouseJoinResponse;
import com.triples.rougether.userapi.house.dto.HousePreviewResponse;
import com.triples.rougether.userapi.house.dto.InviteCodeResponse;
import com.triples.rougether.userapi.house.dto.TransferOwnershipRequest;
import com.triples.rougether.userapi.house.dto.TransferOwnershipResponse;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import com.triples.rougether.userapi.house.service.HouseMemberCommandService;
import com.triples.rougether.userapi.house.service.HouseQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 공동집 API. 생성자는 OWNER 로 즉시 등록되고 초대코드가 발급된다.
@Tag(name = "House", description = "공동집 관련 API")
@RestController
@RequestMapping("/api/v1/houses")
public class HouseController {

    private final HouseCommandService houseCommandService;
    private final HouseJoinService houseJoinService;
    private final HouseQueryService houseQueryService;
    private final HouseMemberCommandService houseMemberCommandService;

    public HouseController(HouseCommandService houseCommandService, HouseJoinService houseJoinService,
                           HouseQueryService houseQueryService,
                           HouseMemberCommandService houseMemberCommandService) {
        this.houseCommandService = houseCommandService;
        this.houseJoinService = houseJoinService;
        this.houseQueryService = houseQueryService;
        this.houseMemberCommandService = houseMemberCommandService;
    }

    @Operation(summary = "집 탐색 목록 조회",
            description = "참여할 수 있는 집 목록을 최신 생성순으로 반환합니다. goalCode 로 목표별 필터링할 수 있습니다.")
    @GetMapping
    public HouseListResponse explore(
            @Parameter(description = "페이지 번호 (0부터)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기") @RequestParam(defaultValue = "20") @Min(1) int size,
            @Parameter(description = "목표 코드 필터 (선택)") @RequestParam(required = false) String goalCode) {
        return houseQueryService.explore(page, size, goalCode);
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

    @Operation(summary = "집 상세 조회",
            description = "참여 중인 집의 설정·목표·레벨·성장 포인트를 조회합니다. 초대코드는 소유자에게만 내려갑니다.")
    @GetMapping("/{houseId}")
    public HouseDetailResponse detail(@CurrentUser AuthUser user,
                                      @Parameter(description = "집 ID") @PathVariable Long houseId) {
        return houseQueryService.getHouseDetail(user.id(), houseId);
    }

    @Operation(summary = "구성원 목록 조회",
            description = "참여 중인 집의 구성원 목록을 가입한 순서로 반환합니다.")
    @GetMapping("/{houseId}/members")
    public HouseMemberListResponse members(@CurrentUser AuthUser user,
                                           @Parameter(description = "집 ID") @PathVariable Long houseId) {
        return houseQueryService.getMembers(user.id(), houseId);
    }

    @Operation(summary = "집 참여",
            description = "탐색한 집에 즉시 가입합니다. 탈퇴 이력이 있으면 기존 구성원 정보를 재활성화합니다.")
    @PostMapping("/{houseId}/join")
    public HouseJoinDetailResponse join(@CurrentUser AuthUser user,
                                        @Parameter(description = "참여할 집 ID") @PathVariable Long houseId) {
        return houseJoinService.join(user.id(), houseId);
    }

    @Operation(summary = "초대코드 재발급",
            description = "집 소유자가 초대코드를 새로 발급합니다. 기존 코드는 즉시 사용할 수 없게 됩니다.")
    @PostMapping("/{houseId}/invite-code")
    public InviteCodeResponse reissueInviteCode(@CurrentUser AuthUser user,
                                                @Parameter(description = "집 ID") @PathVariable Long houseId) {
        return houseCommandService.reissueInviteCode(user.id(), houseId);
    }

    @Operation(summary = "소유권 양도",
            description = "집 소유권을 다른 활성 구성원에게 넘깁니다. 기존 소유자는 일반 구성원이 됩니다.")
    @PostMapping("/{houseId}/transfer-ownership")
    public TransferOwnershipResponse transferOwnership(@CurrentUser AuthUser user,
                                                       @Parameter(description = "집 ID") @PathVariable Long houseId,
                                                       @Valid @RequestBody TransferOwnershipRequest request) {
        return houseMemberCommandService.transferOwnership(user.id(), houseId, request.targetMembershipId());
    }

    @Operation(summary = "초대코드로 집 미리보기",
            description = "참여 전에 초대코드로 집 정보와 정원, 코드 만료 여부를 확인합니다.")
    @GetMapping("/by-code/{inviteCode}")
    public HousePreviewResponse preview(@Parameter(description = "초대코드") @PathVariable String inviteCode) {
        return houseJoinService.preview(inviteCode);
    }
}
