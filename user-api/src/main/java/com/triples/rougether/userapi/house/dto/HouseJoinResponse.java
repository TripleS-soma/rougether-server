package com.triples.rougether.userapi.house.dto;

import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import io.swagger.v3.oas.annotations.media.Schema;

// 집 참여 응답 (spec house/api.md). 즉시가입이라 status 는 항상 ACTIVE.
public record HouseJoinResponse(
        @Schema(description = "구성원 membership ID", example = "12")
        Long membershipId,
        @Schema(description = "참여한 집 ID", example = "1")
        Long houseId,
        @Schema(description = "구성원 상태 (즉시가입이라 ACTIVE)", example = "ACTIVE")
        HouseMemberStatus status) {
}
