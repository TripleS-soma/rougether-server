package com.triples.rougether.userapi.todo.dto;

import com.triples.rougether.userapi.global.validation.FiveMinuteStep;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

public record TodoCreateRequest(
        @Schema(description = "투두 제목", example = "장보기")
        @NotBlank @Size(max = 160) String title,
        @Schema(description = "투두 설명", example = "우유, 계란 구매")
        @Size(max = 2000) String description,
        @Schema(description = "소속 카테고리 ID(미지정이면 미분류). 내 카테고리 목록 조회(GET /api/v1/categories) 응답의 id 값", example = "3")
        Long categoryId,
        @Schema(description = "마감일(YYYY-MM-DD). 미지정이면 마감 없음 — 이 경우 오늘 현황(GET /api/v1/today)에는 노출되지 않음", example = "2026-07-01")
        LocalDate dueDate,
        @Schema(description = "마감 시각(HH:mm, 5분 단위만 허용). 미지정이면 시각 없음(날짜만 마감)", example = "18:00:00")
        @FiveMinuteStep LocalTime dueTime
) {
}
