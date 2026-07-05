package com.triples.rougether.userapi.house.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

// POST /api/v1/houses/{houseId}/transfer-ownership 요청.
public record TransferOwnershipRequest(
        @Schema(description = "새 소유자가 될 구성원의 membership ID. 본인(현재 소유자)이 아닌 같은 집의 활성(ACTIVE) 구성원만 지정 가능. GET /api/v1/houses/{houseId}/members 응답의 membershipId 값", example = "12")
        @NotNull Long targetMembershipId) {
}
