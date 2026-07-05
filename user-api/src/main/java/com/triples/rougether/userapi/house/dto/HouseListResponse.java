package com.triples.rougether.userapi.house.dto;

import com.triples.rougether.domain.house.entity.House;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// GET /api/v1/houses 응답. 목록이 큰 도메인이라 페이지네이션 규약({items, page, size, totalElements}) 적용.
public record HouseListResponse(
        List<HouseSummary> items,
        @Schema(description = "페이지 번호 (0부터)", example = "0")
        int page,
        @Schema(description = "페이지 크기", example = "20")
        int size,
        @Schema(description = "전체 집 수 (goalCode 필터 적용 시 필터된 결과 기준)", example = "42")
        long totalElements) {

    public record HouseSummary(
            @Schema(description = "집 ID. 집 참여(POST /api/v1/houses/{houseId}/join)·상세 조회 경로에 사용", example = "1")
            Long houseId,
            @Schema(description = "집 이름", example = "아침 루틴 하우스")
            String name,
            @Schema(description = "커버 이미지 asset key. CDN base URL 과 조합해 이미지 URL 로 사용", example = "house/1f9d1c2e.png")
            String coverImageKey,
            @Schema(description = "현재 구성원 수", example = "3")
            int currentMemberCount,
            @Schema(description = "최대 구성원 수 (null 이면 무제한)", example = "4")
            Integer maxMembers,
            @Schema(description = "집 레벨", example = "0")
            int level,
            List<GoalSummary> goals) {

        public static HouseSummary of(House house, List<GoalSummary> goals) {
            return new HouseSummary(
                    house.getId(),
                    house.getName(),
                    house.getCoverImageKey(),
                    house.getCurrentMemberCount(),
                    house.getMaxMembers(),
                    house.getLevel(),
                    goals);
        }
    }

    public record GoalSummary(
            @Schema(description = "goal ID. GET /api/v1/goals (목표 마스터 목록) 응답의 id 와 동일한 값", example = "1")
            Long goalId,
            @Schema(description = "goal 코드. 집 탐색(GET /api/v1/houses)의 goalCode 필터 값으로 사용", example = "morning_routine")
            String code,
            @Schema(description = "goal 이름", example = "아침 루틴")
            String name) {
    }
}
