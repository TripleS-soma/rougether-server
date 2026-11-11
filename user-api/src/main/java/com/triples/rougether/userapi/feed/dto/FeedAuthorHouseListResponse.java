package com.triples.rougether.userapi.feed.dto;

import com.triples.rougether.domain.house.entity.HouseDisplayMode;
import com.triples.rougether.domain.house.entity.HouseJoinRequestStatus;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record FeedAuthorHouseListResponse(List<HouseSummary> items) {
    @Schema(name = "FeedAuthorHouseSummary")
    public record HouseSummary(
            Long houseId,
            String name,
            String coverImageKey,
            int level,
            HouseDisplayMode displayMode,
            boolean isPublic,
            @Schema(description = "바로 입주 설정이 꺼져 있어 방장 승인이 필요한지 여부")
            boolean requiresApproval,
            @Schema(description = "현재 실사용자 수. 사람이 들어오면 양보하는 동거 봇은 제외함")
            int currentMemberCount,
            @Schema(description = "최대 구성원 수. null이면 무제한", nullable = true)
            Integer maxMembers,
            boolean isMember,
            boolean isFull,
            @Schema(description = "내 구성원 상태. 이력이 없으면 null", nullable = true)
            HouseMemberStatus myMembershipStatus,
            @Schema(description = "내 신청 상태. PENDING 또는 REJECTED이며 이력이 없거나 이미 구성원이면 null", nullable = true)
            HouseJoinRequestStatus myJoinRequestStatus,
            @Schema(description = "참여 버튼 활성 여부. 승인 대기인 집은 재신청 불가하며 바로 입주 설정인 집은 대기 신청이 있어도 입주 가능함")
            boolean canJoin) { }
}
