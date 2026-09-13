package com.triples.rougether.userapi.routine.dto;

import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public record RoutineLogCreateRequest(
        @Schema(description = "완료(또는 건너뜀) 날짜(YYYY-MM-DD). 미지정 시 오늘(KST). 화면에서 보고 있는 날짜를 그대로 전달. "
                + "COMPLETED 는 오늘(KST 기준) 이전 날짜만(과거 허용), SKIPPED 는 오늘·미래만. 코인·스트릭은 당일 완료에만 반영됩니다", example = "2026-06-29")
        LocalDate routineDate,
        @Schema(description = "기록 종류. 미지정·COMPLETED = 완료 체크. SKIPPED = 그날 발생분 건너뜀(다른 날로 옮길 때, mobile #189) — "
                + "보상·스트릭·단체미션 기여 없이 그 날짜에서 루틴을 숨기고 마감 배치의 FAILED 도 막습니다. 같은 날짜 재요청은 기존 기록을 그대로 돌려줍니다(멱등). "
                + "이미 완료한 날짜는 409 ALREADY_COMPLETED", example = "COMPLETED")
        RoutineLogStatus status
) {
    public RoutineLogCreateRequest(LocalDate routineDate) {
        this(routineDate, null);
    }

    public boolean isSkip() {
        return status == RoutineLogStatus.SKIPPED;
    }
}
