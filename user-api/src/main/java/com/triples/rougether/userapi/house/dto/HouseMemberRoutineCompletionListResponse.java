package com.triples.rougether.userapi.house.dto;

import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

// 집 멤버 루틴 완료 내역. 공개 범위(HOUSE/PUBLIC 카테고리)를 통과한 완료 log 만 내려간다.
public record HouseMemberRoutineCompletionListResponse(
        @Schema(description = "조회에 실제 적용된 시작일(YYYY-MM-DD). from 미지정 시 to 기준 최근 14일", example = "2026-06-25")
        LocalDate from,
        @Schema(description = "조회에 실제 적용된 종료일(YYYY-MM-DD). to 미지정 시 오늘(KST)", example = "2026-07-08")
        LocalDate to,
        @Schema(description = "완료 내역 목록. 완료 날짜 내림차순, 같은 날짜면 완료 시각 내림차순")
        List<CompletionSummary> items) {

    public record CompletionSummary(
            @Schema(description = "완료 날짜(YYYY-MM-DD)", example = "2026-07-08")
            LocalDate routineDate,
            @Schema(description = "완료 시각", example = "2026-07-08T07:12:30Z")
            Instant completedAt,
            @Schema(description = "완료한 루틴 ID (버전 id — 스케줄 수정 시 바뀔 수 있음)", example = "12")
            Long routineId,
            @Schema(description = "루틴 계보 루트 ID. 버전이 바뀌어도 불변 — 프론트에서 같은 루틴 묶음 key 로 사용", example = "10")
            Long originRoutineId,
            @Schema(description = "루틴 제목", example = "아침 운동")
            String title,
            @Schema(description = "루틴의 카테고리 ID (조회 대상 회원 소유의 카테고리)", example = "3")
            Long categoryId) {

        public static CompletionSummary of(RoutineLog log) {
            Routine routine = log.getRoutine();
            return new CompletionSummary(
                    log.getRoutineDate(),
                    log.getCompletedAt(),
                    routine.getId(),
                    routine.getOriginRoutineId(),
                    routine.getTitle(),
                    routine.getCategory() != null ? routine.getCategory().getId() : null);
        }
    }
}
