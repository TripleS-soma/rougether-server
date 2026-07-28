package com.triples.rougether.userapi.house.dto;

import com.triples.rougether.domain.house.entity.HouseJoinRequest;
import com.triples.rougether.domain.house.entity.HouseJoinRequestStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record HouseJoinRequestResponse(
        @Schema(description = "입주 신청 ID", example = "21")
        Long requestId,
        @Schema(description = "신청한 집 ID", example = "1")
        Long houseId,
        @Schema(description = "신청자 회원 ID", example = "7")
        Long userId,
        @Schema(description = "신청자 닉네임. 온보딩 전이면 null", example = "루티니")
        String nickname,
        @Schema(description = "신청 상태", example = "PENDING")
        HouseJoinRequestStatus status,
        @Schema(description = "신청 시각")
        Instant requestedAt) {

    public static HouseJoinRequestResponse of(HouseJoinRequest request) {
        return new HouseJoinRequestResponse(
                request.getId(),
                request.getHouse().getId(),
                request.getUser().getId(),
                request.getUser().getNickname(),
                request.getStatus(),
                request.getRequestedAt());
    }
}
