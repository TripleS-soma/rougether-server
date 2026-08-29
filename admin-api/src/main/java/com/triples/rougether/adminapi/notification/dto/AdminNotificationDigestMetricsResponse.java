package com.triples.rougether.adminapi.notification.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record AdminNotificationDigestMetricsResponse(
        List<DayMetric> days,
        Instant generatedAt) {

    public record DayMetric(
            LocalDate date,
            long createdCount,
            long pendingCount,
            long sentCount,
            long blockedCount,
            long failedCount,
            double sentRate,
            long conversionMeasuredCount,
            long convertedCount,
            long conversionPendingCount,
            double conversionRate) {

        public static DayMetric of(LocalDate date,
                                   long createdCount,
                                   long pendingCount,
                                   long sentCount,
                                   long blockedCount,
                                   long failedCount,
                                   long conversionMeasuredCount,
                                   long convertedCount,
                                   long conversionPendingCount) {
            double sentRate = createdCount == 0 ? 0.0 : sentCount * 100.0 / createdCount;
            double conversionRate = conversionMeasuredCount == 0 ? 0.0
                    : convertedCount * 100.0 / conversionMeasuredCount;
            return new DayMetric(
                    date,
                    createdCount,
                    pendingCount,
                    sentCount,
                    blockedCount,
                    failedCount,
                    sentRate,
                    conversionMeasuredCount,
                    convertedCount,
                    conversionPendingCount,
                    conversionRate);
        }
    }
}
