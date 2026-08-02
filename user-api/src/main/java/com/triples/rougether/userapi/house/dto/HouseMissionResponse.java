package com.triples.rougether.userapi.house.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.triples.rougether.domain.house.entity.HouseMission;
import com.triples.rougether.domain.house.entity.HouseMissionStatus;
import com.triples.rougether.domain.house.entity.HouseMissionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// GET /api/v1/houses/{houseId}/missions/{missionId} 응답. 구성원 전용.
public record HouseMissionResponse(
        @Schema(description = "미션 ID", example = "3")
        Long missionId,
        @Schema(description = "미션 제목", example = "이번 주 다같이 루틴 지키기")
        String title,
        @Schema(description = "미션 유형. DAILY_MEMBER_RATE(일일 구성원 달성률, 매일 반복), WEEKLY_MEMBER_COUNT(주간 구성원 달성 횟수)", example = "WEEKLY_MEMBER_COUNT")
        HouseMissionType missionType,
        @Schema(description = "목표 수치. WEEKLY 는 기여 합산 목표(1~1000), DAILY 는 오늘 달성률 %(1~100)", example = "20")
        int targetValue,
        @Schema(description = "현재 진행 수치. WEEKLY 는 구성원 기여 누적 합, DAILY 는 오늘(KST) 기여 멤버 비율 %(내림)", example = "12")
        long currentValue,
        @Schema(description = "상태. ACTIVE(진행 중), COMPLETED(달성 — 보상 지급됨, WEEKLY 전용), EXPIRED(기간 만료 — endsAt 경과 시 매시 배치로 전이). DAILY 는 COMPLETED 전환 없이 매일 반복", example = "ACTIVE")
        HouseMissionStatus status,
        @Schema(description = "시작 시각 (null 이면 즉시 시작)")
        Instant startsAt,
        @Schema(description = "종료 시각 (null 이면 무기한)")
        Instant endsAt,
        @Schema(description = "내 누적 기여 수치 (유형 무관 누적 체크 횟수)", example = "3")
        int myContribution,
        @Schema(description = "목표 달성 여부. WEEKLY 는 currentValue >= targetValue, DAILY 는 오늘 달성률 기준. 달성 시 보상 받기(claim) 가능", example = "false")
        boolean achieved,
        @Schema(description = "오늘 보상 수령 여부 (DAILY 전용, WEEKLY 는 null 생략). true 면 오늘은 claim 불가, 내일 다시 가능", example = "false")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean todayClaimed,
        @Schema(description = "생성 시각")
        Instant createdAt) {

    // WEEKLY 응답 (todayClaimed 없음 — 기존 계약 유지)
    public static HouseMissionResponse of(HouseMission mission, long currentValue, int myContribution) {
        return build(mission, currentValue, myContribution,
                currentValue >= mission.getTargetValue(), null);
    }

    // DAILY 응답 - currentValue 는 오늘 달성률 %, achieved 는 오늘 판정
    public static HouseMissionResponse of(HouseMission mission, long currentValue, int myContribution,
                                          boolean achieved, boolean todayClaimed) {
        return build(mission, currentValue, myContribution, achieved, todayClaimed);
    }

    private static HouseMissionResponse build(HouseMission mission, long currentValue, int myContribution,
                                              boolean achieved, Boolean todayClaimed) {
        return new HouseMissionResponse(
                mission.getId(),
                mission.getTitle(),
                mission.getMissionType(),
                mission.getTargetValue(),
                currentValue,
                mission.getStatus(),
                mission.getStartsAt(),
                mission.getEndsAt(),
                myContribution,
                achieved,
                todayClaimed,
                mission.getCreatedAt());
    }
}
