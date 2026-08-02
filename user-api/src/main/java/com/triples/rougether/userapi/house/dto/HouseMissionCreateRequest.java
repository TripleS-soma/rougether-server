package com.triples.rougether.userapi.house.dto;

import com.triples.rougether.domain.house.entity.HouseMissionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

// POST /api/v1/houses/{houseId}/missions 요청. 소유자 전용.
public record HouseMissionCreateRequest(
        @Schema(description = "미션 제목 (1~160자)", example = "이번 주 다같이 루틴 지키기")
        @NotBlank @Size(min = 1, max = 160) String title,
        @Schema(description = "미션 유형. DAILY_MEMBER_RATE(일일 구성원 달성률, 매일 반복), WEEKLY_MEMBER_COUNT(주간 구성원 달성 횟수). "
                + "STREAK_DAYS 는 MVP 미지원(400 HOUSE_MISSION_TYPE_NOT_SUPPORTED)", example = "WEEKLY_MEMBER_COUNT")
        @NotNull HouseMissionType missionType,
        @Schema(description = "목표 수치. WEEKLY 는 기여 합산 목표(1~1000), DAILY 는 오늘 달성률 %(1~100, 초과 시 400 HOUSE_MISSION_TARGET_INVALID)", example = "20")
        @Min(1) @Max(1000) int targetValue,
        @Schema(description = "시작 시각 (선택, 미지정 시 즉시 시작)", example = "2026-07-06T00:00:00Z")
        Instant startsAt,
        @Schema(description = "종료 시각 (선택, 미지정 시 무기한. 지정 시 startsAt 보다 뒤여야 함)", example = "2026-07-13T00:00:00Z")
        Instant endsAt) {
}
