package com.triples.rougether.userapi.house.dto;

import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import io.swagger.v3.oas.annotations.media.Schema;

// 집 참여 응답 (spec house/api.md).
// 집 공용 코드(소유자 공유)는 즉시가입, 구성원 개인 코드는 방장 승인 대기 신청을 만든다.
public record HouseJoinResponse(
        @Schema(description = "구성원 membership ID. 구성원 강퇴(DELETE /api/v1/houses/{houseId}/members/{membershipId})·소유권 양도 대상 지정에 사용. 승인 대기(pendingApproval=true)면 아직 구성원이 아니라 null", example = "12")
        Long membershipId,
        @Schema(description = "참여한 집 ID", example = "1")
        Long houseId,
        @Schema(description = "구성원 상태 (즉시가입 시 항상 ACTIVE). ACTIVE(활동 중), LEFT(탈퇴 — 재참여 가능), KICKED(강퇴 — 재참여 불가). 승인 대기면 null", example = "ACTIVE")
        HouseMemberStatus status,
        @Schema(description = "방장 승인 대기 여부. 집 공용 코드(소유자 공유)는 false(즉시가입), 구성원 개인 코드는 true(입주 신청 생성, 방장 수락 시 입주 확정)", example = "false")
        boolean pendingApproval,
        @Schema(description = "생성된 입주 신청 ID (pendingApproval=true 일 때만). 방장의 수락/거절(POST /api/v1/houses/{houseId}/join-requests/{requestId}/accept·reject) 대상", example = "7")
        Long joinRequestId) {

    public static HouseJoinResponse joined(Long membershipId, Long houseId, HouseMemberStatus status) {
        return new HouseJoinResponse(membershipId, houseId, status, false, null);
    }

    public static HouseJoinResponse pending(Long houseId, Long joinRequestId) {
        return new HouseJoinResponse(null, houseId, null, true, joinRequestId);
    }
}
