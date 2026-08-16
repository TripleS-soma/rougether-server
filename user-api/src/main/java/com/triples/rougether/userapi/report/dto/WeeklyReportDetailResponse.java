package com.triples.rougether.userapi.report.dto;

import com.triples.rougether.domain.report.WeeklyReportSections;
import com.triples.rougether.domain.report.WeeklyReportStats;
import com.triples.rougether.domain.report.entity.WeeklyReport;
import com.triples.rougether.domain.report.entity.WeeklyReportStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

// 상세 = 목록 항목 + 전체 통계 + LLM 섹션. FALLBACK 회고는 섹션 배열이 비어 있다.
public record WeeklyReportDetailResponse(
        @Schema(description = "회고 ID", example = "12")
        Long reportId,
        @Schema(description = "회고 대상 주의 시작일(일요일, YYYY-MM-DD, KST)", example = "2026-08-09")
        LocalDate weekStartDate,
        @Schema(description = "회고 대상 주의 종료일(토요일, YYYY-MM-DD, KST)", example = "2026-08-15")
        LocalDate weekEndDate,
        @Schema(description = "회고 상태: GENERATED(AI 회고 문장 포함), FALLBACK(AI 생성 실패 — 통계와 고정 요약 문구만 있고 highlights·failurePatterns·suggestions는 빈 배열)",
                example = "GENERATED")
        WeeklyReportStatus status,
        @Schema(description = "주간 완료율(0~1, 소수 둘째 자리). completedCount / scheduledCount", example = "0.67")
        double completionRate,
        @Schema(description = "그 주 완료한 루틴 수행 횟수", example = "4")
        int completedCount,
        @Schema(description = "그 주 수행 대상이었던 루틴 횟수(완료+실패)", example = "6")
        int scheduledCount,
        @Schema(description = "회고 요약 문단(최대 300자). FALLBACK이면 고정 문구", example = "이번 주는 6회 중 4회를 완료했어요.")
        String summary,
        @Schema(description = "회고 생성 시각(ISO-8601)")
        Instant generatedAt,
        @Schema(description = "주간 통계(요일별·루틴별·스트릭). 배치가 생성 시점에 계산한 값")
        WeeklyStatsResponse stats,
        @Schema(description = "잘한 점(최대 3개, 각 80자 이내). FALLBACK이면 빈 배열", example = "[\"주말 이틀 모두 달리기를 지켰어요\"]")
        List<String> highlights,
        @Schema(description = "반복된 실패 패턴(최대 3개, 각 80자 이내). 실패가 없거나 FALLBACK이면 빈 배열", example = "[\"화요일 저녁 루틴이 두 번 빠졌어요\"]")
        List<String> failurePatterns,
        @Schema(description = "다음 주 제안(최대 3개, 각 80자 이내). FALLBACK이면 빈 배열", example = "[\"화요일은 알람을 30분 앞당겨 보세요\"]")
        List<String> suggestions) {

    public static WeeklyReportDetailResponse of(WeeklyReport report, WeeklyReportStats stats,
                                                WeeklyReportSections sections) {
        return new WeeklyReportDetailResponse(
                report.getId(),
                report.getWeekStartDate(),
                report.getWeekEndDate(),
                report.getStatus(),
                stats.completionRate(),
                stats.completedCount(),
                stats.scheduledCount(),
                report.getSummary(),
                report.getGeneratedAt(),
                WeeklyStatsResponse.from(stats),
                sections.highlights(),
                sections.failurePatterns(),
                sections.suggestions());
    }
}
