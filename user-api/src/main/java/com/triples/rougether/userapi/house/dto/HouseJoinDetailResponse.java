package com.triples.rougether.userapi.house.dto;

import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// POST /api/v1/houses/{houseId}/join 응답 (spec house/api.md - 탐색 참여는 membership 전체 필드).
public record HouseJoinDetailResponse(
        @Schema(description = "구성원 membership ID", example = "12")
        Long membershipId,
        @Schema(description = "참여한 집 ID", example = "1")
        Long houseId,
        @Schema(description = "참여한 회원 ID", example = "7")
        Long userId,
        @Schema(description = "역할 (참여자는 MEMBER)", example = "MEMBER")
        HouseMemberRole role,
        @Schema(description = "구성원 상태 (즉시가입이라 ACTIVE)", example = "ACTIVE")
        HouseMemberStatus status,
        @Schema(description = "참여 시각")
        Instant joinedAt) {

    public static HouseJoinDetailResponse of(HouseMember member, Long houseId, Long userId) {
        return new HouseJoinDetailResponse(
                member.getId(), houseId, userId, member.getRole(), member.getStatus(), member.getJoinedAt());
    }
}
