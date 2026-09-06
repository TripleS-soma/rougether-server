package com.triples.rougether.userapi.appicon.dto;

import com.triples.rougether.domain.appicon.AppIconState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record AppIconResponse(
        @Schema(description = "앱에 포함된 아이콘과 매핑할 상태 코드") AppIconState state,
        @Schema(description = "고양이 말투의 상태 문구") String message,
        @Schema(description = "서버 상태 판정 시각, UTC") Instant evaluatedAt,
        @Schema(description = "마지막 명시적 foreground 활동 시각. 아직 기록하지 않았으면 null")
        Instant lastForegroundAt,
        @Schema(description = "시간 경과에 따른 다음 재평가 시각. OS 아이콘 변경 예약을 보장하지 않음")
        Instant nextEvaluationAt,
        @Schema(description = "KST 오늘 기준 유효 루틴 스트릭") int currentStreak,
        @Schema(description = "당일 루틴 또는 오늘 실제 완료한 투두가 하나 이상 있는지") boolean completedToday
) {
}
