package com.triples.rougether.userapi.routine.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public record RoutineLogCreateRequest(
        @Schema(description = "완료 날짜(YYYY-MM-DD). 미지정 시 오늘(KST). 화면에서 보고 있는 날짜를 그대로 전달. 오늘(KST 기준) 이전 날짜만 사용(과거 허용). 코인·스트릭은 당일 완료에만 반영됩니다", example = "2026-06-29")
        LocalDate routineDate
) {
}
