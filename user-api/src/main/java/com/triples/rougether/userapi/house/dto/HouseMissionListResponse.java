package com.triples.rougether.userapi.house.dto;

import com.triples.rougether.domain.house.entity.HouseMission;
import com.triples.rougether.domain.house.entity.HouseMissionStatus;
import com.triples.rougether.domain.house.entity.HouseMissionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

// GET /api/v1/houses/{houseId}/missions 응답. 구성원 전용, 최신 생성순.
public record HouseMissionListResponse(List<MissionSummary> items) {

    public record MissionSummary(
            @Schema(description = "미션 ID. 상세(GET .../missions/{missionId})·기여(POST .../contribute)·보상(POST .../claim) 경로에 사용", example = "3")
            Long missionId,
            @Schema(description = "미션 제목", example = "이번 주 다같이 루틴 지키기")
            String title,
            @Schema(description = "미션 유형. DAILY_MEMBER_RATE(일일 구성원 달성률), WEEKLY_MEMBER_COUNT(주간 구성원 달성 횟수)", example = "WEEKLY_MEMBER_COUNT")
            HouseMissionType missionType,
            @Schema(description = "목표 수치", example = "20")
            int targetValue,
            @Schema(description = "현재 진행 수치 (구성원 기여 합산)", example = "12")
            long currentValue,
            @Schema(description = "상태. ACTIVE(진행 중), COMPLETED(달성 — 보상 지급됨), EXPIRED(기간 만료)", example = "ACTIVE")
            HouseMissionStatus status,
            @Schema(description = "시작 시각 (null 이면 즉시 시작)")
            Instant startsAt,
            @Schema(description = "종료 시각 (null 이면 무기한)")
            Instant endsAt,
            @Schema(description = "생성 시각. 목록은 이 값 내림차순(최신 생성순) 정렬")
            Instant createdAt) {

        public static MissionSummary of(HouseMission mission, long currentValue) {
            return new MissionSummary(
                    mission.getId(),
                    mission.getTitle(),
                    mission.getMissionType(),
                    mission.getTargetValue(),
                    currentValue,
                    mission.getStatus(),
                    mission.getStartsAt(),
                    mission.getEndsAt(),
                    mission.getCreatedAt());
        }
    }
}
