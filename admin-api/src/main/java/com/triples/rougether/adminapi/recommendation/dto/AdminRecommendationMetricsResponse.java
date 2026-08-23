package com.triples.rougether.adminapi.recommendation.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

// 관리자 AI 조정 추천 퍼널 관측 응답(#332). 추천 메시지·제안 본문은 싣지 않고 주차별 건수와 비율만 전달한다.
public record AdminRecommendationMetricsResponse(
        List<WeekMetric> weeks,
        Instant generatedAt) {

    // 주 = 추천 생성 시각(KST) 소속 일~토 주. 이번 주 배치 생성분이 바로 보이도록 진행 중 주가 최신 행이다.
    public record WeekMetric(
            LocalDate weekStartDate,
            LocalDate weekEndDate,
            boolean inProgress,
            long createdCount,
            long acceptedCount,
            long dismissedCount,
            long expiredCount,
            long pendingCount,
            double acceptedRate,
            double respondedRate,
            long effectMeasuredCount,
            long effectPendingCount,
            long effectUnmeasurableCount,
            Double avgCompletionDeltaPp) {

        public static WeekMetric of(LocalDate weekStartDate,
                                    LocalDate weekEndDate,
                                    boolean inProgress,
                                    long createdCount,
                                    long acceptedCount,
                                    long dismissedCount,
                                    long expiredCount,
                                    long pendingCount,
                                    long effectMeasuredCount,
                                    long effectPendingCount,
                                    long effectUnmeasurableCount,
                                    Double avgCompletionDeltaPp) {
            double acceptedRate = createdCount == 0 ? 0.0 : acceptedCount * 100.0 / createdCount;
            // 반응률 = 수락+무시. 만료·대기는 무반응 - 제안이 눈에 띄기는 하는지 보는 지표
            double respondedRate = createdCount == 0 ? 0.0
                    : (acceptedCount + dismissedCount) * 100.0 / createdCount;
            return new WeekMetric(
                    weekStartDate,
                    weekEndDate,
                    inProgress,
                    createdCount,
                    acceptedCount,
                    dismissedCount,
                    expiredCount,
                    pendingCount,
                    acceptedRate,
                    respondedRate,
                    effectMeasuredCount,
                    effectPendingCount,
                    effectUnmeasurableCount,
                    avgCompletionDeltaPp);
        }
    }
}
