package com.triples.rougether.userapi.recommendation.dto;

import com.triples.rougether.domain.recommendation.entity.RecommendationType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record RecommendationItem(
        @Schema(description = "추천 ID. 수락/무시 API 의 path 값", example = "1")
        Long recommendationId,
        @Schema(description = "추천 타입. 허용값: ADJUST_DAYS(반복 요일·빈도 조정)", example = "ADJUST_DAYS")
        RecommendationType type,
        @Schema(description = "사용자에게 보여줄 제안 문구(한국어)",
                example = "『아침 러닝』 수요일 수행이 3주 연속 실패했어요. 수요일을 빼고 나머지 요일에 집중해 보면 어떨까요?")
        String message,
        @Schema(description = "대상 루틴의 현재 버전 id (GET /api/v1/routines 응답의 id)", example = "42")
        Long routineId,
        @Schema(description = "대상 루틴의 버전 계보 루트 id (루틴 응답의 originRoutineId 와 동일)", example = "40")
        Long originRoutineId,
        @Schema(description = "대상 루틴 제목(현재 버전 기준)", example = "아침 러닝")
        String routineTitle,
        @Schema(description = "제안 스케줄 절대값. 수락 시 이대로 적용됨")
        RecommendationProposalResponse proposal,
        @Schema(description = "추천 생성 시각")
        Instant createdAt,
        @Schema(description = "만료 시각(생성 + 7일). 만료된 추천은 목록에서 제외됨")
        Instant expiresAt
) {
}
