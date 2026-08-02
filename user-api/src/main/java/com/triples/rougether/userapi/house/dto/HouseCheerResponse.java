package com.triples.rougether.userapi.house.dto;

import com.triples.rougether.domain.house.entity.HouseMemberCheer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

// 응원 보내기 응답.
public record HouseCheerResponse(
        @Schema(description = "응원 기록 ID", example = "31")
        Long cheerId,
        @Schema(description = "집 ID", example = "1")
        Long houseId,
        @Schema(description = "응원 대상 membership ID (요청 경로의 값)", example = "12")
        Long targetMembershipId,
        @Schema(description = "응원 대상 회원 ID", example = "8")
        Long targetUserId,
        @Schema(description = "응원 타입", example = "support")
        String type,
        @Schema(description = "응원 날짜 (KST). 같은 대상에게 같은 타입은 하루 5회", example = "2026-07-20")
        LocalDate cheerDate) {

    public static HouseCheerResponse of(HouseMemberCheer cheer, Long houseId, Long targetMembershipId) {
        return new HouseCheerResponse(
                cheer.getId(),
                houseId,
                targetMembershipId,
                cheer.getTarget().getId(),
                cheer.getCheerType(),
                cheer.getCheerDate());
    }
}
