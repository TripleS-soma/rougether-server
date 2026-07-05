package com.triples.rougether.userapi.house.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.house.dto.HouseMissionClaimResponse;
import com.triples.rougether.userapi.house.dto.HouseMissionContributeResponse;
import com.triples.rougether.userapi.house.dto.HouseMissionCreateRequest;
import com.triples.rougether.userapi.house.dto.HouseMissionListResponse;
import com.triples.rougether.userapi.house.dto.HouseMissionResponse;
import com.triples.rougether.userapi.house.service.HouseMissionService;
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

// 단체 미션 - 목록/생성/상세/기여(임시)/보상. 전부 해당 집의 ACTIVE 구성원 전용.
@Tag(name = "HouseMission", description = "공동집 단체 미션 관련 API")
@RestController
@RequestMapping("/api/v1/houses/{houseId}/missions")
public class HouseMissionController {

    private final HouseMissionService houseMissionService;

    public HouseMissionController(HouseMissionService houseMissionService) {
        this.houseMissionService = houseMissionService;
    }

    @Operation(summary = "단체 미션 목록 조회",
            description = "집의 단체 미션 목록을 최신 생성순으로 반환합니다. 해당 집의 ACTIVE 구성원만 조회할 수 있습니다. "
                    + "currentValue 는 구성원 기여 합산이며, targetValue 이상이 되면 보상 받기(claim)가 가능합니다.")
    @GetMapping
    public HouseMissionListResponse getMissions(@CurrentUser AuthUser user,
                                                @Parameter(description = "집 ID. GET /api/v1/me/houses (내 집 목록) 응답의 houseId 값")
                                                @PathVariable Long houseId) {
        return houseMissionService.getMissions(user.id(), houseId);
    }

    @Operation(summary = "단체 미션 등록",
            description = "집에 단체 미션을 등록합니다. 소유자(OWNER)만 등록할 수 있습니다. "
                    + "미션 유형은 MVP 에서 DAILY_MEMBER_RATE(일일 구성원 달성률)·WEEKLY_MEMBER_COUNT(주간 구성원 달성 횟수) 2종만 지원하며 "
                    + "STREAK_DAYS 는 400 을 반환합니다. startsAt·endsAt 은 선택이고, 둘 다 지정하면 endsAt 이 startsAt 보다 뒤여야 합니다. "
                    + "등록된 미션은 ACTIVE 상태로 시작합니다.")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public HouseMissionResponse create(@CurrentUser AuthUser user,
                                       @Parameter(description = "집 ID. GET /api/v1/me/houses (내 집 목록) 응답의 houseId 값")
                                       @PathVariable Long houseId,
                                       @Valid @RequestBody HouseMissionCreateRequest request) {
        return houseMissionService.create(user.id(), houseId, request);
    }

    @Operation(summary = "단체 미션 상세 조회",
            description = "미션 하나의 진행 상황을 반환합니다. 해당 집의 ACTIVE 구성원만 조회할 수 있으며, "
                    + "전체 진행 수치(currentValue)와 내 누적 기여(myContribution)를 함께 내려줍니다.")
    @GetMapping("/{missionId}")
    public HouseMissionResponse getMission(@CurrentUser AuthUser user,
                                           @Parameter(description = "집 ID. GET /api/v1/me/houses (내 집 목록) 응답의 houseId 값")
                                           @PathVariable Long houseId,
                                           @Parameter(description = "미션 ID. GET /api/v1/houses/{houseId}/missions (단체 미션 목록) 응답의 missionId 값")
                                           @PathVariable Long missionId) {
        return houseMissionService.getMission(user.id(), houseId, missionId);
    }

    @Operation(summary = "단체 미션 기여 (임시)",
            description = "미션 진행 수치에 본인 기여 +1 을 반영합니다. 루틴 완료 이벤트 연동 전까지 사용하는 임시 API 입니다. "
                    + "해당 집의 ACTIVE 구성원만, KST(Asia/Seoul) 기준 하루 1회만 기여할 수 있으며 "
                    + "진행 중(ACTIVE)이고 미션 기간 내일 때만 가능합니다. "
                    + "이미 오늘 기여했으면 409 HOUSE_MISSION_ALREADY_CONTRIBUTED 를 반환합니다.")
    @PostMapping("/{missionId}/contribute")
    public HouseMissionContributeResponse contribute(@CurrentUser AuthUser user,
                                                     @Parameter(description = "집 ID. GET /api/v1/me/houses (내 집 목록) 응답의 houseId 값")
                                                     @PathVariable Long houseId,
                                                     @Parameter(description = "미션 ID. GET /api/v1/houses/{houseId}/missions (단체 미션 목록) 응답의 missionId 값")
                                                     @PathVariable Long missionId) {
        return houseMissionService.contribute(user.id(), houseId, missionId);
    }

    @Operation(summary = "단체 미션 보상 받기",
            description = "목표를 달성한(currentValue >= targetValue) 미션의 보상을 받습니다. 해당 집의 ACTIVE 구성원 누구나 실행할 수 있고 "
                    + "미션당 최초 1회만 가능합니다. 성공 시 미션이 COMPLETED 로 전환되고 집 성장 포인트가 +100 적립되며 "
                    + "(개인 재화 지급 없음, 레벨 = 성장 포인트 100당 1), "
                    + "목표 미달성이면 409 HOUSE_MISSION_NOT_ACHIEVED, 이미 보상을 받았으면 409 HOUSE_MISSION_ALREADY_CLAIMED 를 반환합니다.")
    @PostMapping("/{missionId}/claim")
    public HouseMissionClaimResponse claim(@CurrentUser AuthUser user,
                                           @Parameter(description = "집 ID. GET /api/v1/me/houses (내 집 목록) 응답의 houseId 값")
                                           @PathVariable Long houseId,
                                           @Parameter(description = "미션 ID. GET /api/v1/houses/{houseId}/missions (단체 미션 목록) 응답의 missionId 값")
                                           @PathVariable Long missionId) {
        return houseMissionService.claim(user.id(), houseId, missionId);
    }
}
