package com.triples.rougether.userapi.house.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// POST /api/v1/houses/{houseId}/missions/{missionId}/contribute 응답. 임시 기여 API.
public record HouseMissionContributeResponse(
        @Schema(description = "미션 ID", example = "3")
        Long missionId,
        @Schema(description = "내 누적 기여 수치 (이번 기여 +1 반영)", example = "4")
        int myContribution,
        @Schema(description = "현재 진행 수치 (구성원 기여 합산, 이번 기여 반영)", example = "13")
        long currentValue,
        @Schema(description = "목표 달성 여부 (currentValue >= targetValue). 달성 시 보상 받기(claim) 가능", example = "false")
        boolean achieved) {
}
