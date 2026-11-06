package com.triples.rougether.userapi.house.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// POST /api/v1/houses/{houseId}/missions/{missionId}/contribute 응답. 미션 수행 체크(기여) 결과.
public record HouseMissionContributeResponse(
        @Schema(description = "미션 ID", example = "3")
        Long missionId,
        @Schema(description = "내 누적 기여 수치 (이번 기여 +1 반영, 유형 무관 누적)", example = "4")
        int myContribution,
        @Schema(description = "현재 진행 수치 (이번 기여 반영). WEEKLY 는 기여 누적 합, DAILY_MEMBER_RATE 는 오늘(KST) 기여 멤버 비율 %(내림), DAILY_MEMBER_COUNT 는 오늘(KST) 기여 멤버 수", example = "13")
        long currentValue,
        @Schema(description = "목표 달성 여부. WEEKLY 는 currentValue >= targetValue, DAILY 는 오늘 기준(RATE 는 달성률, COUNT 는 기여 멤버 수). 달성 시 보상 받기(claim) 가능", example = "false")
        boolean achieved) {
}
