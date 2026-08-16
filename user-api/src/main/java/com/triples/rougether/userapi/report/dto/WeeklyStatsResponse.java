package com.triples.rougether.userapi.report.dto;

import com.triples.rougether.domain.report.WeeklyReportStats;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.DayOfWeek;
import java.util.List;

// weekly_reports.stats_json(WeeklyReportStats) 을 그대로 응답 형태로 옮긴 것. 배치가 계산한 값이라 서버가 재계산하지 않는다.
public record WeeklyStatsResponse(
        @Schema(description = "그 주 수행 대상이었던 루틴 횟수(완료+실패)", example = "6")
        int scheduledCount,
        @Schema(description = "완료 횟수", example = "4")
        int completedCount,
        @Schema(description = "실패 횟수(하루 마감 시점까지 완료하지 않은 수행)", example = "2")
        int failedCount,
        @Schema(description = "완료율(0~1, 소수 둘째 자리)", example = "0.67")
        double completionRate,
        @Schema(description = "요일별 완료/실패. 일요일부터 토요일까지 항상 7개, 순서 고정")
        List<WeekdayStatResponse> byWeekday,
        @Schema(description = "루틴별 완료/실패. 같은 루틴의 수정 버전은 하나로 합쳐 표시하며, 그 주에 기록이 있는 루틴만 포함")
        List<RoutineStatResponse> byRoutine,
        @Schema(description = "회고 생성 시점의 스트릭 스냅샷")
        StreakResponse streak) {

    public record WeekdayStatResponse(
            @Schema(description = "요일: SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY(일~토)", example = "SUNDAY")
            DayOfWeek dayOfWeek,
            @Schema(description = "그 요일 완료 횟수", example = "1")
            int completed,
            @Schema(description = "그 요일 실패 횟수", example = "0")
            int failed) {
    }

    public record RoutineStatResponse(
            @Schema(description = "루틴 계보 ID(수정 버전이 여러 개여도 같은 값). 루틴 목록 조회(GET /api/v1/routines)의 originRoutineId(없으면 id)와 대응", example = "41")
            Long lineageId,
            @Schema(description = "루틴 제목(그 주에 기록된 가장 최근 버전 기준)", example = "달리기")
            String title,
            @Schema(description = "카테고리 이름. 미분류이거나 카테고리가 삭제됐으면 null", example = "건강")
            String categoryName,
            @Schema(description = "그 루틴의 완료 횟수", example = "3")
            int completed,
            @Schema(description = "그 루틴의 실패 횟수", example = "1")
            int failed) {
    }

    public record StreakResponse(
            @Schema(description = "회고 생성 시점의 현재 연속 일수", example = "5")
            int currentCount,
            @Schema(description = "회고 생성 시점의 최장 연속 일수", example = "12")
            int longestCount) {
    }

    public static WeeklyStatsResponse from(WeeklyReportStats stats) {
        return new WeeklyStatsResponse(
                stats.scheduledCount(),
                stats.completedCount(),
                stats.failedCount(),
                stats.completionRate(),
                stats.byWeekday().stream()
                        .map(d -> new WeekdayStatResponse(d.dayOfWeek(), d.completed(), d.failed()))
                        .toList(),
                stats.byRoutine().stream()
                        .map(r -> new RoutineStatResponse(r.lineageId(), r.title(), r.categoryName(),
                                r.completed(), r.failed()))
                        .toList(),
                new StreakResponse(stats.streak().currentCount(), stats.streak().longestCount()));
    }
}
