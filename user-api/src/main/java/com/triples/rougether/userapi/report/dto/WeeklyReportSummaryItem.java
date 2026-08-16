package com.triples.rougether.userapi.report.dto;

import com.triples.rougether.domain.report.WeeklyReportStats;
import com.triples.rougether.domain.report.entity.WeeklyReport;
import com.triples.rougether.domain.report.entity.WeeklyReportStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;

// 목록 항목. 상세와 공통인 머리 부분(주 범위·상태·핵심 통계·요약).
public record WeeklyReportSummaryItem(
        @Schema(description = "회고 ID. 주간 회고 상세 조회(GET /api/v1/reports/weekly/{reportId})의 reportId로 사용", example = "12")
        Long reportId,
        @Schema(description = "회고 대상 주의 시작일(일요일, YYYY-MM-DD, KST)", example = "2026-08-09")
        LocalDate weekStartDate,
        @Schema(description = "회고 대상 주의 종료일(토요일, YYYY-MM-DD, KST)", example = "2026-08-15")
        LocalDate weekEndDate,
        @Schema(description = "회고 상태: GENERATED(AI 회고 문장 포함), FALLBACK(AI 생성 실패 — 통계와 고정 요약 문구만 있고 상세의 AI 섹션 배열은 비어 있음)",
                example = "GENERATED")
        WeeklyReportStatus status,
        @Schema(description = "주간 완료율(0~1, 소수 둘째 자리). completedCount / scheduledCount", example = "0.67")
        double completionRate,
        @Schema(description = "그 주 완료한 루틴 수행 횟수", example = "4")
        int completedCount,
        @Schema(description = "그 주 수행 대상이었던 루틴 횟수(완료+실패)", example = "6")
        int scheduledCount,
        @Schema(description = "회고 요약 문단(최대 300자). FALLBACK이면 고정 문구", example = "이번 주는 6회 중 4회를 완료했어요. 화요일 저녁 루틴이 두 번 빠졌지만 주말엔 모두 지켰어요.")
        String summary,
        @Schema(description = "회고 생성 시각(ISO-8601)")
        Instant generatedAt) {

    public static WeeklyReportSummaryItem of(WeeklyReport report, WeeklyReportStats stats) {
        return new WeeklyReportSummaryItem(
                report.getId(),
                report.getWeekStartDate(),
                report.getWeekEndDate(),
                report.getStatus(),
                stats.completionRate(),
                stats.completedCount(),
                stats.scheduledCount(),
                report.getSummary(),
                report.getGeneratedAt());
    }
}
