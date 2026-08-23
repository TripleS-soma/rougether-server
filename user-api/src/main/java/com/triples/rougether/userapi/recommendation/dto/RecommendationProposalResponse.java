package com.triples.rougether.userapi.recommendation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// routine_recommendations.proposal JSON 의 역직렬화 대상이기도 함 — 필드명이 저장 스키마의 정본
public record RecommendationProposalResponse(
        @Schema(description = "제안 반복 유형. MVP 조정 추천은 항상 WEEKLY", example = "WEEKLY")
        String repeatType,
        @Schema(description = "제안 반복 요일(MON~SUN, 루틴 등록의 repeatDays.daysOfWeek 와 같은 표기). "
                + "수락 시 이 값이 루틴 스케줄에 그대로 적용됨", example = "[\"MON\",\"WED\",\"FRI\"]")
        List<String> daysOfWeek
) {
}
