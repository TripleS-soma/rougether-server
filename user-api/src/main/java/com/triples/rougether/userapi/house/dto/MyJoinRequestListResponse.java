package com.triples.rougether.userapi.house.dto;

import com.triples.rougether.domain.house.entity.HouseJoinRequest;
import com.triples.rougether.domain.house.entity.HouseJoinRequestStatus;
import com.triples.rougether.userapi.house.dto.HouseListResponse.GoalSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

// GET /api/v1/me/join-requests 응답. 승인 대기 카드 표시용으로 집 요약을 함께 내린다.
public record MyJoinRequestListResponse(List<MyJoinRequestSummary> items) {

    public record MyJoinRequestSummary(
            @Schema(description = "입주 신청 ID. 신청 철회(DELETE /api/v1/me/join-requests/{requestId}) 경로에 사용",
                    example = "21")
            Long requestId,
            @Schema(description = "신청한 집 ID", example = "1")
            Long houseId,
            @Schema(description = "집 이름", example = "아침 루틴 하우스")
            String houseName,
            @Schema(description = "커버 이미지 asset key. CDN base URL 과 조합해 이미지 URL 로 사용",
                    example = "house/1f9d1c2e.png")
            String coverImageKey,
            List<GoalSummary> goals,
            @Schema(description = "신청 상태", example = "PENDING")
            HouseJoinRequestStatus status,
            @Schema(description = "신청 시각")
            Instant requestedAt) {

        public static MyJoinRequestSummary of(HouseJoinRequest request, List<GoalSummary> goals) {
            return new MyJoinRequestSummary(
                    request.getId(),
                    request.getHouse().getId(),
                    request.getHouse().getName(),
                    request.getHouse().getCoverImageKey(),
                    goals,
                    request.getStatus(),
                    request.getRequestedAt());
        }
    }
}
