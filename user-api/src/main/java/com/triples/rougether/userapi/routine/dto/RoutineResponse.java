package com.triples.rougether.userapi.routine.dto;

import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalTime;

public record RoutineResponse(
        @Schema(description = "루틴 ID", example = "1")
        Long id,
        @Schema(description = "루틴 제목", example = "아침 운동")
        String title,
        @Schema(description = "소속 카테고리 ID(미분류면 null)", example = "3")
        Long categoryId,
        @Schema(description = "인증 방식")
        AuthType authType,
        @Schema(description = "루틴 상태")
        RoutineStatus status,
        @Schema(description = "반복 유형", example = "WEEKLY")
        String repeatType,
        @Schema(description = "반복 설정(WEEKLY일 때 요일)")
        RepeatDays repeatDays,
        @Schema(description = "수행 예정 시각(HH:mm:ss)", example = "07:00:00")
        LocalTime scheduledTime,
        @Schema(description = "시작일(ISO-8601)", example = "2026-07-01")
        LocalDate startsOn,
        @Schema(description = "종료일(ISO-8601)", example = "2026-12-31")
        LocalDate endsOn
) {

    public static RoutineResponse from(Routine routine) {
        Category category = routine.getCategory();
        return new RoutineResponse(
                routine.getId(),
                routine.getTitle(),
                category != null ? category.getId() : null,
                routine.getAuthType(),
                routine.getStatus(),
                routine.getRepeatType(),
                RepeatDays.fromJson(routine.getRepeatDays()),
                routine.getScheduledTime(),
                routine.getStartsOn(),
                routine.getEndsOn()
        );
    }
}
