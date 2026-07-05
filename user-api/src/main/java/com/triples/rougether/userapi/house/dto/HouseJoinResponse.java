package com.triples.rougether.userapi.house.dto;

import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import io.swagger.v3.oas.annotations.media.Schema;

// 집 참여 응답 (spec house/api.md). 즉시가입이라 status 는 항상 ACTIVE.
public record HouseJoinResponse(
        @Schema(description = "구성원 membership ID. 구성원 강퇴(DELETE /api/v1/houses/{houseId}/members/{membershipId})·소유권 양도 대상 지정에 사용", example = "12")
        Long membershipId,
        @Schema(description = "참여한 집 ID", example = "1")
        Long houseId,
        @Schema(description = "구성원 상태 (즉시가입이라 항상 ACTIVE). ACTIVE(활동 중), LEFT(탈퇴 — 재참여 가능), KICKED(강퇴 — 재참여 불가)", example = "ACTIVE")
        HouseMemberStatus status) {
}
