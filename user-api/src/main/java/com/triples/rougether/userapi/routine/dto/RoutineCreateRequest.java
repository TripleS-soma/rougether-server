package com.triples.rougether.userapi.routine.dto;

import com.triples.rougether.domain.routine.entity.AuthType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

public record RoutineCreateRequest(
        @Schema(description = "루틴 제목", example = "아침 운동")
        @NotBlank @Size(max = 160) String title,
        @Schema(description = "소속 카테고리 ID(미지정이면 미분류). 내 카테고리 목록 조회(GET /api/v1/categories) 응답의 id 값", example = "3")
        Long categoryId,
        @Schema(description = "인증 방식. 허용값: CHECK(체크형), PHOTO(사진 인증형)", example = "CHECK")
        @NotNull AuthType authType,
        @Schema(description = "반복 유형. 허용값: DAILY(매일), WEEKLY(요일 지정 — repeatDays 필요)", example = "WEEKLY")
        @NotBlank @Pattern(regexp = "DAILY|WEEKLY") String repeatType,
        @Schema(description = "반복 설정. repeatType이 WEEKLY일 때만 요일 지정, DAILY면 생략")
        RepeatDays repeatDays,
        @Schema(description = "수행 예정 시각(HH:mm:ss). 루틴 목록·오늘 현황의 정렬 기준, 미지정 가능", example = "07:00:00")
        LocalTime scheduledTime,
        @Schema(description = "시작일(YYYY-MM-DD). 미지정이면 생성일(오늘)로 지정됨. 오늘 이전 과거일은 불가. 오늘 현황은 시작일~종료일 범위의 루틴만 대상으로 봄", example = "2026-07-01")
        LocalDate startsOn,
        @Schema(description = "종료일(YYYY-MM-DD). 미지정이면 제한 없음", example = "2026-12-31")
        LocalDate endsOn
) {
}
