package com.triples.rougether.userapi.today.dto;

import com.triples.rougether.domain.routine.entity.AuthType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalTime;

public record TodayRoutineItem(
        @Schema(description = "루틴 ID. 루틴 완료 체크/취소 등 루틴 API 경로의 {id}로 사용", example = "1")
        Long id,
        @Schema(description = "루틴 제목", example = "아침 운동")
        String title,
        @Schema(description = "수행 예정 시각(HH:mm:ss, 미지정이면 null)", example = "07:00:00")
        LocalTime scheduledTime,
        @Schema(description = "인증 방식. 허용값: CHECK(체크형), PHOTO(사진 인증형)", example = "CHECK")
        AuthType authType,
        @Schema(description = "기준일 완료 여부. false면 완료 체크(POST /api/v1/routines/{id}/logs), true면 완료 취소(DELETE /api/v1/routines/{id}/logs)로 전환")
        boolean completed
) {
}
