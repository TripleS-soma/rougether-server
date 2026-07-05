package com.triples.rougether.userapi.house.dto;

import com.triples.rougether.domain.house.entity.HouseMissionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

// POST /api/v1/houses/{houseId}/missions/{missionId}/claim 응답. 집 성장 포인트 지급 결과.
public record HouseMissionClaimResponse(
        @Schema(description = "미션 ID", example = "3")
        Long missionId,
        @Schema(description = "상태 (claim 성공 시 COMPLETED)", example = "COMPLETED")
        HouseMissionStatus status,
        @Schema(description = "이번에 지급된 집 성장 포인트", example = "100")
        int grantedGrowthPoints,
        @Schema(description = "지급 후 집 성장 포인트", example = "300")
        int houseGrowthPoints,
        @Schema(description = "지급 후 집 레벨 (성장 포인트 100당 1레벨)", example = "3")
        int houseLevel) {
}
