package com.triples.rougether.userapi.house.web;

import com.triples.rougether.domain.house.entity.HouseJoinRequestStatus;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.house.dto.MyJoinRequestListResponse;
import com.triples.rougether.userapi.house.service.HouseQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 내가 보낸 입주 신청. 집 탭 스위처의 "승인 대기" 카드 표시용(#255).
@Tag(name = "House", description = "공동집 관련 API")
@RestController
@RequestMapping("/api/v1/me/join-requests")
public class MyJoinRequestController {

    private final HouseQueryService houseQueryService;

    public MyJoinRequestController(HouseQueryService houseQueryService) {
        this.houseQueryService = houseQueryService;
    }

    @Operation(summary = "내가 보낸 입주 신청 목록 조회",
            description = "로그인한 회원이 보낸 입주 신청 목록을 최신 신청 순으로 반환합니다. "
                    + "탐색에서 보낸 신청과 구성원 개인 초대코드로 보낸 승인 대기 신청이 모두 포함되며, "
                    + "삭제된 집의 신청은 제외됩니다. 기본은 대기 중(PENDING) 신청만 조회합니다.")
    @GetMapping
    public MyJoinRequestListResponse getMyJoinRequests(
            @CurrentUser AuthUser user,
            @Parameter(description = "조회할 신청 상태. 생략 시 PENDING")
            @RequestParam(defaultValue = "PENDING") HouseJoinRequestStatus status) {
        return houseQueryService.getMyJoinRequests(user.id(), status);
    }
}
