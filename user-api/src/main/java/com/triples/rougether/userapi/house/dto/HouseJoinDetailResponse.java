package com.triples.rougether.userapi.house.dto;

import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// POST /api/v1/houses/{houseId}/join 응답 (spec house/api.md - 탐색 참여는 membership 전체 필드).
public record HouseJoinDetailResponse(
        @Schema(description = "구성원 membership ID. 구성원 강퇴(DELETE /api/v1/houses/{houseId}/members/{membershipId})·소유권 양도 대상 지정에 사용", example = "12")
        Long membershipId,
        @Schema(description = "참여한 집 ID", example = "1")
        Long houseId,
        @Schema(description = "참여한 회원 ID", example = "7")
        Long userId,
        @Schema(description = "역할 (참여자는 항상 MEMBER). OWNER(소유자), MEMBER(일반 구성원)", example = "MEMBER")
        HouseMemberRole role,
        @Schema(description = "구성원 상태 (즉시가입이라 항상 ACTIVE). ACTIVE(활동 중), LEFT(탈퇴 — 재참여 가능), KICKED(강퇴 — 재참여 불가)", example = "ACTIVE")
        HouseMemberStatus status,
        @Schema(description = "참여 시각")
        Instant joinedAt) {

    public static HouseJoinDetailResponse of(HouseMember member, Long houseId, Long userId) {
        return new HouseJoinDetailResponse(
                member.getId(), houseId, userId, member.getRole(), member.getStatus(), member.getJoinedAt());
    }
}
