package com.triples.rougether.userapi.today.dto;

import com.triples.rougether.domain.routine.entity.AuthType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalTime;

public record TodayRoutineItem(
        @Schema(description = "루틴 ID", example = "1")
        Long id,
        @Schema(description = "루틴 제목", example = "아침 운동")
        String title,
        @Schema(description = "수행 예정 시각(HH:mm:ss, 미지정이면 null)", example = "07:00:00")
        LocalTime scheduledTime,
        @Schema(description = "인증 방식")
        AuthType authType,
        @Schema(description = "오늘 완료 여부")
        boolean completed
) {
}
