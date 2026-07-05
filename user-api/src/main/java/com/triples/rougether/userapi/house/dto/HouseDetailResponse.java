package com.triples.rougether.userapi.house.dto;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.userapi.house.dto.HouseListResponse.GoalSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

// GET /api/v1/houses/{houseId} 응답. 구성원 전용. 초대코드는 소유자에게만 값이 내려간다.
public record HouseDetailResponse(
        @Schema(description = "집 ID", example = "1")
        Long houseId,
        @Schema(description = "집 이름", example = "아침 루틴 하우스")
        String name,
        @Schema(description = "집 소개", example = "같이 아침 루틴 지켜요")
        String description,
        @Schema(description = "커버 이미지 asset key", example = "house/1f9d1c2e.png")
        String coverImageKey,
        @Schema(description = "최대 구성원 수 (null 이면 무제한)", example = "4")
        Integer maxMembers,
        @Schema(description = "현재 구성원 수", example = "3")
        int currentMemberCount,
        @Schema(description = "집 레벨", example = "0")
        int level,
        @Schema(description = "성장 포인트", example = "120")
        int growthPoints,
        List<GoalSummary> goals,
        @Schema(description = "이 집에서 내 역할", example = "OWNER")
        HouseMemberRole myRole,
        @Schema(description = "초대코드 (소유자에게만 값, 그 외 null)", example = "ABCD2345")
        String inviteCode,
        @Schema(description = "초대코드 만료 시각 (소유자에게만 값, 그 외 null)")
        Instant inviteExpiresAt) {

    public static HouseDetailResponse of(House house, HouseMemberRole myRole, List<GoalSummary> goals) {
        boolean owner = myRole == HouseMemberRole.OWNER;
        return new HouseDetailResponse(
                house.getId(),
                house.getName(),
                house.getDescription(),
                house.getCoverImageKey(),
                house.getMaxMembers(),
                house.getCurrentMemberCount(),
                house.getLevel(),
                house.getGrowthPoints(),
                goals,
                myRole,
                owner ? house.getInviteCode() : null,
                owner ? house.getInviteExpiresAt() : null);
    }
}
