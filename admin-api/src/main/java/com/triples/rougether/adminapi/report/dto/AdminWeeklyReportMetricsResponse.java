package com.triples.rougether.adminapi.report.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

// 관리자 AI 주간 회고 관측 응답. 회고 본문·통계 원문은 싣지 않고 주차별 건수와 비율만 전달한다.
public record AdminWeeklyReportMetricsResponse(
        List<WeekMetric> weeks,
        Instant generatedAt) {

    // 주차 상태. 화면 pill 과 1:1 대응한다.
    public enum WeekState {
        PENDING,          // 그 주 첫 배치(일요일 00:30 KST) 이전이고 아직 생성 없음
        NO_TARGET,        // 대상 사용자도 회고도 없음
        NOT_GENERATED,    // 대상은 있는데 회고가 0건 — 배치 보류(LLM stub·day-end 미완료)·실패 의심
        BASELINE_SHIFTED, // 생성 건수가 대상 수를 초과 — 배치 이후 log 삭제·완료 취소로 분모가 줄어든 상태
        PARTIAL,          // 일부 사용자 누락(skip)
        COMPLETE          // 대상 전원 생성
    }

    public record WeekMetric(
            LocalDate weekStartDate,
            LocalDate weekEndDate,
            WeekState state,
            long eligibleUsers,
            long generatedCount,
            long fallbackCount,
            long missingCount,
            long viewedCount,
            double coverageRate,
            double fallbackRate,
            double viewedRate,
            List<String> models,
            Instant lastGeneratedAt) {

        public static WeekMetric of(LocalDate weekStartDate,
                                    LocalDate weekEndDate,
                                    boolean beforeFirstTrigger,
                                    long eligibleUsers,
                                    long generatedCount,
                                    long fallbackCount,
                                    long viewedCount,
                                    List<String> models,
                                    Instant lastGeneratedAt) {
            long produced = generatedCount + fallbackCount;
            long missing = Math.max(0, eligibleUsers - produced);
            double coverageRate = eligibleUsers == 0 ? 0.0 : Math.min(100.0, produced * 100.0 / eligibleUsers);
            double fallbackRate = produced == 0 ? 0.0 : fallbackCount * 100.0 / produced;
            // 열람률(#332)의 분모는 만들어진 회고(생성+FALLBACK) - 도달(push) 성공 여부는 여기서 구분하지 않는다
            double viewedRate = produced == 0 ? 0.0 : viewedCount * 100.0 / produced;
            return new WeekMetric(
                    weekStartDate,
                    weekEndDate,
                    stateOf(beforeFirstTrigger, eligibleUsers, produced, missing),
                    eligibleUsers,
                    generatedCount,
                    fallbackCount,
                    missing,
                    viewedCount,
                    coverageRate,
                    fallbackRate,
                    viewedRate,
                    models,
                    lastGeneratedAt);
        }

        static WeekState stateOf(boolean beforeFirstTrigger, long eligibleUsers, long produced, long missing) {
            if (produced == 0 && beforeFirstTrigger) {
                return WeekState.PENDING;
            }
            if (eligibleUsers == 0 && produced == 0) {
                return WeekState.NO_TARGET;
            }
            if (produced == 0) {
                return WeekState.NOT_GENERATED;
            }
            if (produced > eligibleUsers) {
                return WeekState.BASELINE_SHIFTED;
            }
            return missing > 0 ? WeekState.PARTIAL : WeekState.COMPLETE;
        }
    }
}
