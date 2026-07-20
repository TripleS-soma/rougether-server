package com.triples.rougether.userapi.todo.dto;

import com.triples.rougether.userapi.global.validation.FiveMinuteStep;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

public record TodoUpdateRequest(
        @Schema(description = "투두 제목(최대 160자). 미지정(null)이거나 공백이면 기존 값 유지", example = "장보기")
        @Size(max = 160) String title,
        @Schema(description = "투두 설명(최대 2000자). 미지정(null)이면 기존 값 유지", example = "우유, 계란 구매")
        @Size(max = 2000) String description,
        @Schema(description = "소속 카테고리 ID(지정하면 해당 카테고리로 변경, null이면 기존 카테고리 유지). 내 카테고리 목록 조회(GET /api/v1/categories) 응답의 id 값", example = "3")
        Long categoryId,
        @Schema(description = "마감일(YYYY-MM-DD). 미지정(null)이면 기존 값 유지", example = "2026-07-01")
        LocalDate dueDate,
        @Schema(description = "마감 시각(HH:mm, 5분 단위만 허용). null이면 해제합니다.", example = "18:00:00")
        @FiveMinuteStep LocalTime dueTime
) {
}
