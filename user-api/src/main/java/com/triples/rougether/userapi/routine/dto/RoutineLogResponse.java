package com.triples.rougether.userapi.routine.dto;

import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.house.dto.HouseMissionContributeResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;

public record RoutineLogResponse(
        @Schema(description = "완료 기록 ID", example = "1")
        Long id,
        @Schema(description = "완료 날짜(YYYY-MM-DD)", example = "2026-06-29")
        LocalDate routineDate,
        @Schema(description = "완료 상태. 허용값: PENDING(미수행), COMPLETED(완료), FAILED(실패)", example = "COMPLETED")
        RoutineLogStatus status,
        @Schema(description = "완료 시각(ISO-8601)", example = "2026-06-29T07:00:00Z")
        Instant completedAt,
        @Schema(description = "보상 재화 종류. 허용값: COIN(루틴 실천 보상), DIAMOND(아이템 구매)", example = "COIN")
        CurrencyType rewardCurrencyType,
        @Schema(description = "실제 지급된 보상 금액. 오늘 완료는 코인 10이나, 루틴·투두 합산 일일 상한(50코인)의 남은 한도가 "
                + "10보다 적으면 남은 만큼만 지급되고, 한도를 다 썼거나 과거 날짜 완료면 0 지급", example = "10")
        int rewardAmount,
        @Schema(description = "스트릭 요약. 오늘 첫 완료면 갱신된 값이며, 저장 상태를 변경하지 않는 응답에서도 "
                + "이미 끊긴 currentCount는 기준일에 맞춰 0으로 표시")
        StreakSummaryResponse streak,
        @Schema(description = "연동 단체미션 자동 기여 결과(수행 체크 API 응답과 동일 형태). "
                + "이번 완료로 기여가 반영된 경우에만 값이 있고, 미연동 루틴이거나 기여가 건너뛰어진 경우"
                + "(오늘 이미 기여함·미션 비활성/기간 밖/삭제·집 비구성원·과거 날짜 완료)는 null. "
                + "완료 취소를 해도 이 기여는 회수되지 않음")
        HouseMissionContributeResponse houseMissionContribution
) {

    public static RoutineLogResponse from(RoutineLog log, Streak streak,
                                          HouseMissionContributeResponse houseMissionContribution,
                                          LocalDate referenceDate) {
        return new RoutineLogResponse(
                log.getId(),
                log.getRoutineDate(),
                log.getStatus(),
                log.getCompletedAt(),
                log.getRewardCurrencyType(),
                log.getRewardAmount(),
                // 과거 완료는 스트릭을 건드리지 않으므로 스트릭이 아직 없을 수 있음
                streak != null
                        ? StreakSummaryResponse.from(streak, referenceDate)
                        : new StreakSummaryResponse(0, 0, null),
                houseMissionContribution
        );
    }
}
