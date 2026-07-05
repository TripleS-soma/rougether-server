package com.triples.rougether.userapi.house.dto;

import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

// GET /api/v1/me/houses 응답. 내가 속한(ACTIVE) 집들 - 먼저 가입한 집 먼저.
public record MyHouseListResponse(List<MyHouseSummary> items) {

    public record MyHouseSummary(
            @Schema(description = "집 ID. 집 상세 조회 등 /api/v1/houses/{houseId} 경로에 사용", example = "1")
            Long houseId,
            @Schema(description = "집 이름", example = "아침 루틴 하우스")
            String name,
            @Schema(description = "커버 이미지 asset key. CDN base URL 과 조합해 이미지 URL 로 사용", example = "house/1f9d1c2e.png")
            String coverImageKey,
            @Schema(description = "집 레벨", example = "0")
            int level,
            @Schema(description = "현재 구성원 수", example = "3")
            int currentMemberCount,
            @Schema(description = "최대 구성원 수 (null 이면 무제한)", example = "4")
            Integer maxMembers,
            @Schema(description = "이 집에서 내 역할. OWNER(소유자 — 설정 수정·초대코드 재발급·강퇴·소유권 양도 가능), MEMBER(일반 구성원)", example = "OWNER")
            HouseMemberRole myRole,
            @Schema(description = "내 가입 시각. 목록은 이 값 오름차순(먼저 가입한 집 먼저) 정렬")
            Instant joinedAt) {

        public static MyHouseSummary of(HouseMember member) {
            return new MyHouseSummary(
                    member.getHouse().getId(),
                    member.getHouse().getName(),
                    member.getHouse().getCoverImageKey(),
                    member.getHouse().getLevel(),
                    member.getHouse().getCurrentMemberCount(),
                    member.getHouse().getMaxMembers(),
                    member.getRole(),
                    member.getJoinedAt());
        }
    }
}
