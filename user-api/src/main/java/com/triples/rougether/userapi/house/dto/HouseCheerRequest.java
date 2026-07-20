package com.triples.rougether.userapi.house.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

// POST /api/v1/houses/{houseId}/members/{membershipId}/cheer 요청.
public record HouseCheerRequest(
        @Schema(description = "응원 타입. 허용값 3종: great(잘하고 있어!)/support(응원해요!)/best(오늘도 최고!)",
                example = "support")
        @NotBlank String type) {
}
