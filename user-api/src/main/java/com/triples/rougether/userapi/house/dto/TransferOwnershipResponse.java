package com.triples.rougether.userapi.house.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// 소유권 양도 응답.
public record TransferOwnershipResponse(
        @Schema(description = "집 ID", example = "1")
        Long houseId,
        @Schema(description = "새 소유자의 membership ID", example = "12")
        Long newOwnerMembershipId,
        @Schema(description = "새 소유자의 회원 ID", example = "8")
        Long newOwnerUserId) {
}
