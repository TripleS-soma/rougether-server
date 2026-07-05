package com.triples.rougether.userapi.house.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

// POST /api/v1/houses/{houseId}/transfer-ownership 요청.
public record TransferOwnershipRequest(
        @Schema(description = "새 소유자가 될 구성원의 membership ID", example = "12")
        @NotNull Long targetMembershipId) {
}
