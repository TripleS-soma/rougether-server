package com.triples.rougether.userapi.routine.dto;

import com.triples.rougether.domain.routine.entity.AuthType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

public record RoutineUpdateRequest(
        @Schema(description = "루틴 제목(최대 160자). 미지정(null)이거나 공백이면 기존 값 유지", example = "아침 운동")
        @Size(max = 160) String title,
        @Schema(description = "소속 카테고리 ID(지정하면 해당 카테고리로 변경, null이면 기존 카테고리 유지). 내 카테고리 목록 조회(GET /api/v1/categories) 응답의 id 값", example = "3")
        Long categoryId,
        @Schema(description = "인증 방식. 허용값: CHECK(체크형), PHOTO(사진 인증형). 미지정(null)이면 기존 값 유지", example = "CHECK")
        AuthType authType,
        @Schema(description = "반복 유형. 허용값: DAILY(매일), WEEKLY(요일 지정 — repeatDays 함께 전달), "
                + "BIWEEKLY(격주 — repeatDays.daysOfWeek + startsOn 필요), "
                + "MONTHLY(매월 — repeatDays.dayOfMonth 필요), "
                + "YEARLY(매년 — repeatDays.month/day 필요). 미지정(null)이면 기존 값 유지", example = "WEEKLY")
        @Pattern(regexp = "DAILY|WEEKLY|BIWEEKLY|MONTHLY|YEARLY") String repeatType,
        @Schema(description = "반복 설정. repeatType이 WEEKLY/BIWEEKLY면 daysOfWeek, "
                + "MONTHLY면 dayOfMonth, YEARLY면 month/day 지정. 미지정(null)이면 기존 값 유지")
        RepeatDays repeatDays,
        @Schema(description = "수행 예정 시각(HH:mm:ss). null이면 해제합니다.", example = "07:00:00")
        LocalTime scheduledTime,
        @Schema(description = "시작일(YYYY-MM-DD). 미지정(null)이면 기존 값 유지", example = "2026-07-01")
        LocalDate startsOn,
        @Schema(description = "종료일(YYYY-MM-DD). null이면 해제합니다.", example = "2026-12-31")
        LocalDate endsOn
) {
}
