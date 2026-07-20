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
import com.triples.rougether.userapi.house.dto.HousePreviewDetailResponse;
import com.triples.rougether.userapi.house.dto.HousePreviewResponse;
import com.triples.rougether.userapi.house.dto.HouseUpdateRequest;
import com.triples.rougether.userapi.house.dto.HouseUpdateResponse;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
            description = "참여할 수 있는 집 목록을 최신 생성순으로 반환합니다. 로그인한 회원 누구나 호출할 수 있습니다. "
                    + "삭제되지 않은 모든 집이 대상이며, 본인이 이미 가입한 집과 정원이 가득 찬 집도 목록에 포함됩니다. "
                    + "goalCode 를 주면 해당 목표가 연결된 집만 반환하고, 미지정 또는 빈 값이면 전체를 반환합니다. "
                    + "미지정 시 page=0, size=20 으로 조회합니다. goalCode 는 GET /api/v1/goals 응답의 code 값을 사용합니다.")
    @GetMapping
    public HouseListResponse explore(
            @Parameter(description = "페이지 번호 (0부터)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기") @RequestParam(defaultValue = "20") @Min(1) int size,
            @Parameter(description = "목표 코드 필터 (선택). GET /api/v1/goals 응답의 code 값") @RequestParam(required = false) String goalCode) {
        return houseQueryService.explore(page, size, goalCode);
    }

    @Operation(summary = "공동집 생성",
            description = "새 공동집을 만들고 생성자를 OWNER 구성원으로 자동 등록하며 초대코드를 발급합니다. "
                    + "생성 직후 현재 구성원 수는 1(생성자), 집 레벨과 성장 포인트는 0에서 시작합니다. "
                    + "maxMembers 미지정 시 4로 설정됩니다. 초대코드는 영대문자+숫자 8자로 발급 시점부터 7일간 유효합니다. "
                    + "goalIds 는 GET /api/v1/goals (목표 마스터 목록) 응답의 id 값을 사용하며, 활성 goal 1~3개만 허용되고 중복 id 는 자동 제거됩니다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HouseCreateResponse create(@CurrentUser AuthUser user,
                                      @Valid @RequestBody HouseCreateRequest request) {
        return houseCommandService.create(user.id(), request);
    }

    @Operation(summary = "초대코드로 집 참여",
            description = "초대코드로 집에 즉시 가입합니다. 승인 절차 없이 MEMBER 역할·ACTIVE 상태로 바로 등록되며, 참여가 확정되면 집의 현재 구성원 수가 1 증가합니다. "
                    + "만료 전 초대코드로, 정원에 여유가 있는 집에, 아직 이 집의 활성 구성원이 아니고 강퇴 이력이 없는 회원만 참여할 수 있습니다. "
                    + "탈퇴(LEFT) 이력이 있으면 기존 구성원 정보를 재활성화합니다(membershipId 유지, 가입 시각 갱신). "
                    + "inviteCode 는 집 생성(POST /api/v1/houses) 응답 또는 집 상세(GET /api/v1/houses/{houseId}) 응답(소유자)의 inviteCode 값을 사용합니다.")
    @PostMapping("/join-by-code")
    public HouseJoinResponse joinByCode(@CurrentUser AuthUser user,
                                        @Valid @RequestBody HouseJoinByCodeRequest request) {
        return houseJoinService.joinByCode(user.id(), request.inviteCode());
    }

    @Operation(summary = "집 상세 조회",
            description = "참여 중인 집의 설정·목표·레벨·성장 포인트를 조회합니다. 해당 집의 활성(ACTIVE) 구성원만 조회할 수 있습니다. "
                    + "초대코드(inviteCode)와 만료 시각(inviteExpiresAt)은 소유자에게만 값이 내려가고 일반 구성원에게는 null 입니다.")
    @GetMapping("/{houseId}")
    public HouseDetailResponse detail(@CurrentUser AuthUser user,
                                      @Parameter(description = "집 ID. GET /api/v1/houses 또는 GET /api/v1/me/houses 응답의 houseId 값") @PathVariable Long houseId) {
        return houseQueryService.getHouseDetail(user.id(), houseId);
    }

    // 비구성원용 미리보기. 상세와 달리 구성원 검증 없음(전체공개), myRole·inviteCode 없음.
    @Operation(summary = "집 미리보기 (비구성원 가능)",
            description = "탐색 목록에서 선택한 집을 참여 전에 살펴봅니다. 로그인한 회원 누구나(해당 집 비구성원·강퇴 이력자 포함) 조회할 수 있습니다. "
                    + "구성원용 상세와 동일한 집 정보(이름·소개·커버·인원·레벨·목표)를 내려주되, 구성원 전용 필드(myRole·inviteCode)는 없습니다. "
                    + "isMember 가 true 면 요청자가 이미 이 집의 활성 구성원이므로 상세 화면으로 전환하고, "
                    + "isFull 이 true 면 정원 초과라 참여할 수 없으니 가입 버튼을 비활성화합니다. "
                    + "단체 출석률 필드는 출석 저장 구조(#168) 구현 후 추가될 예정입니다.")
    @GetMapping("/{houseId}/preview")
    public HousePreviewDetailResponse preview(@CurrentUser AuthUser user,
                                              @Parameter(description = "집 ID. GET /api/v1/houses (탐색 목록) 응답의 houseId 값") @PathVariable Long houseId) {
        return houseQueryService.getPreview(user.id(), houseId);
    }

    @Operation(summary = "구성원 목록 조회",
            description = "참여 중인 집의 구성원 목록을 가입한 순서(가입 시각 오름차순)로 반환합니다. 해당 집의 활성(ACTIVE) 구성원만 조회할 수 있습니다. "
                    + "활성 구성원만 노출되며 탈퇴·강퇴한 구성원은 포함되지 않습니다. 생성자(OWNER)가 첫 번째로 옵니다.")
    @GetMapping("/{houseId}/members")
    public HouseMemberListResponse members(@CurrentUser AuthUser user,
                                           @Parameter(description = "집 ID. GET /api/v1/houses 또는 GET /api/v1/me/houses 응답의 houseId 값") @PathVariable Long houseId) {
        return houseQueryService.getMembers(user.id(), houseId);
    }

    @Operation(summary = "집 참여",
            description = "탐색한 집에 houseId 로 즉시 가입합니다. 초대코드 참여와 동일한 정책으로, 승인 절차 없이 MEMBER 역할·ACTIVE 상태로 바로 등록되며 참여가 확정되면 집의 현재 구성원 수가 1 증가합니다. "
                    + "정원에 여유가 있는 집에, 아직 이 집의 활성 구성원이 아니고 강퇴 이력이 없는 회원만 참여할 수 있습니다. "
                    + "탈퇴(LEFT) 이력이 있으면 기존 구성원 정보를 재활성화합니다(membershipId 유지, 가입 시각 갱신).")
    @PostMapping("/{houseId}/join")
    public HouseJoinDetailResponse join(@CurrentUser AuthUser user,
                                        @Parameter(description = "참여할 집 ID. GET /api/v1/houses (탐색 목록) 응답의 houseId 값") @PathVariable Long houseId) {
        return houseJoinService.join(user.id(), houseId);
    }

    @Operation(summary = "집 설정 수정",
            description = "집 이름·소개글·대표 이미지·최대 인원을 수정합니다. 집 소유자만 호출할 수 있습니다. "
                    + "부분 수정으로 보내지 않은(null) 필드는 기존 값이 그대로 유지됩니다. "
                    + "maxMembers 는 현재 구성원 수 이상의 값으로만 변경할 수 있습니다.")
    @PutMapping("/{houseId}")
    public HouseUpdateResponse updateSettings(@CurrentUser AuthUser user,
                                              @Parameter(description = "집 ID. GET /api/v1/houses 또는 GET /api/v1/me/houses 응답의 houseId 값") @PathVariable Long houseId,
                                              @Valid @RequestBody HouseUpdateRequest request) {
        return houseCommandService.updateSettings(user.id(), houseId, request);
    }

    @Operation(summary = "초대코드 재발급",
            description = "초대코드를 새로 발급합니다. 집 소유자만 호출할 수 있습니다. "
                    + "새 코드는 영대문자+숫자 8자로 재발급 시점부터 7일간 유효하며, 기존 코드는 즉시 사용할 수 없게 됩니다.")
    @PostMapping("/{houseId}/invite-code")
    public InviteCodeResponse reissueInviteCode(@CurrentUser AuthUser user,
                                                @Parameter(description = "집 ID. GET /api/v1/houses 또는 GET /api/v1/me/houses 응답의 houseId 값") @PathVariable Long houseId) {
        return houseCommandService.reissueInviteCode(user.id(), houseId);
    }

    @Operation(summary = "소유권 양도",
            description = "집 소유권을 다른 활성 구성원에게 넘깁니다. 집 소유자만 호출할 수 있습니다. "
                    + "대상은 같은 집의 본인이 아닌 활성(ACTIVE) 구성원이어야 합니다. "
                    + "양도가 완료되면 대상 구성원이 OWNER 가 되고 기존 소유자는 일반 구성원(MEMBER)이 됩니다. "
                    + "targetMembershipId 는 구성원 목록(GET /api/v1/houses/{houseId}/members) 응답의 membershipId 값을 사용합니다.")
    @PostMapping("/{houseId}/transfer-ownership")
    public TransferOwnershipResponse transferOwnership(@CurrentUser AuthUser user,
                                                       @Parameter(description = "집 ID. GET /api/v1/houses 또는 GET /api/v1/me/houses 응답의 houseId 값") @PathVariable Long houseId,
                                                       @Valid @RequestBody TransferOwnershipRequest request) {
        return houseMemberCommandService.transferOwnership(user.id(), houseId, request.targetMembershipId());
    }

    @Operation(summary = "집 탈퇴",
            description = "참여 중인 집에서 나갑니다. 해당 집의 활성(ACTIVE) 구성원만 호출할 수 있습니다. "
                    + "탈퇴하면 상태가 LEFT 로 바뀌고 집의 현재 구성원 수가 1 감소하며, 이후 같은 집에 다시 참여하면 기존 구성원 정보가 재활성화됩니다. "
                    + "소유자는 혼자 남은 경우에 바로 탈퇴할 수 있고, 다른 활성 구성원이 있으면 소유권을 양도한 뒤 탈퇴할 수 있습니다. "
                    + "마지막 구성원이 나가면 집이 정리되어 탐색·조회에서 제외됩니다.")
    @DeleteMapping("/{houseId}/members/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@CurrentUser AuthUser user,
                      @Parameter(description = "집 ID. GET /api/v1/houses 또는 GET /api/v1/me/houses 응답의 houseId 값") @PathVariable Long houseId) {
        houseMemberCommandService.leave(user.id(), houseId);
    }

    @Operation(summary = "구성원 강퇴",
            description = "구성원을 집에서 내보냅니다. 집 소유자만 호출할 수 있으며, 본인이 아닌 같은 집의 활성(ACTIVE) 구성원을 내보낼 수 있습니다. "
                    + "강퇴된 구성원은 상태가 KICKED 로 바뀌어 이 집에 다시 참여할 수 없고, 집의 현재 구성원 수가 1 감소합니다.")
    @DeleteMapping("/{houseId}/members/{membershipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void kick(@CurrentUser AuthUser user,
                     @Parameter(description = "집 ID. GET /api/v1/houses 또는 GET /api/v1/me/houses 응답의 houseId 값") @PathVariable Long houseId,
                     @Parameter(description = "강퇴할 구성원의 membership ID. GET /api/v1/houses/{houseId}/members 응답의 membershipId 값") @PathVariable Long membershipId) {
        houseMemberCommandService.kick(user.id(), houseId, membershipId);
    }

    @Operation(summary = "초대코드로 집 미리보기",
            description = "참여 전에 초대코드로 집 정보와 정원, 코드 만료 여부를 확인합니다. 로그인한 회원 누구나(해당 집 비구성원 포함) 호출할 수 있습니다. "
                    + "만료된 코드도 집 정보가 그대로 반환되며 inviteExpired 필드로 만료 여부를 표시합니다. "
                    + "만료 전 코드로만 참여(POST /api/v1/houses/join-by-code)할 수 있으므로 화면의 만료 안내에 사용합니다.")
    @GetMapping("/by-code/{inviteCode}")
    public HousePreviewResponse preview(
            @Parameter(description = "초대코드 (영대문자+숫자 8자). 집 생성(POST /api/v1/houses) 응답 또는 집 상세(GET /api/v1/houses/{houseId}) 응답(소유자)의 inviteCode 값")
            @PathVariable String inviteCode) {
        return houseJoinService.preview(inviteCode);
    }
}
