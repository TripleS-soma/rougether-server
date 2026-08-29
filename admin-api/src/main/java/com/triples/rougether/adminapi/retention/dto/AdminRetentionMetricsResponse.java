package com.triples.rougether.adminapi.retention.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

// 관리자 KPI 응답. 각 비율은 화면 숫자뿐 아니라 분자·분모·관측 기간을 함께 내려 산식을 재검증할 수 있게 함.
// dayEndFinalizedThroughDate: day-end 배치가 확정한 마지막 routine_date. 완료율 창은 이 날짜까지로 잘린다.
// null 이면 확정 정보를 읽을 수 없는 환경(BATCH_* 미존재) - 완료율에 미확정 날짜가 섞였을 수 있음을 화면에 알린다.
public record AdminRetentionMetricsResponse(
        LocalDate asOfDate,
        Instant generatedAt,
        LocalDate dayEndFinalizedThroughDate,
        DatePeriod cohortPeriod,
        RetentionMetrics retention,
        List<RetentionCohortMetric> retentionCohorts,
        NorthStarMetric northStar,
        List<SegmentMetric> segments) {

    public enum UserSegment {
        OVERALL,
        PERSONAL,
        SHARED
    }

    public record DatePeriod(
            LocalDate fromDate,
            LocalDate toDate,
            int days) {

        public static DatePeriod inclusive(LocalDate fromDate, LocalDate toDate) {
            int days = Math.toIntExact(toDate.toEpochDay() - fromDate.toEpochDay() + 1);
            return new DatePeriod(fromDate, toDate, days);
        }
    }

    public record RetentionMetrics(
            String activityDefinition,
            RetentionMetric d1,
            RetentionMetric d7,
            RetentionMetric d30) {
    }

    // exact-day 리텐션. 분모는 조회 코호트 중 해당 일차에 도달한 활성 비봇 가입자다.
    public record RetentionMetric(
            int dayOffset,
            LocalDate eligibleSignupThroughDate,
            long returnedUserCount,
            long eligibleUserCount,
            double percentage) {

        public static RetentionMetric of(int dayOffset,
                                         LocalDate eligibleSignupThroughDate,
                                         long returnedUserCount,
                                         long eligibleUserCount) {
            return new RetentionMetric(dayOffset, eligibleSignupThroughDate,
                    returnedUserCount, eligibleUserCount,
                    AdminRetentionMetricsResponse.percentage(returnedUserCount, eligibleUserCount));
        }
    }

    // 가입 KST 일자별 exact-day 관측. 아직 해당 일차에 도달하지 않은 point는 null임.
    public record RetentionCohortMetric(
            LocalDate cohortDate,
            long cohortUserCount,
            RetentionPoint d1,
            RetentionPoint d7,
            RetentionPoint d30) {
    }

    public record RetentionPoint(
            int dayOffset,
            long returnedUserCount,
            long eligibleUserCount,
            double percentage) {

        public static RetentionPoint of(int dayOffset, long returnedUserCount, long eligibleUserCount) {
            return new RetentionPoint(dayOffset, returnedUserCount, eligibleUserCount,
                    AdminRetentionMetricsResponse.percentage(returnedUserCount, eligibleUserCount));
        }
    }

    // 최근 7개 KST 일자 중 실제 completed_at 일자가 3일 이상인 사용자 비율.
    // 분모는 활동 여부와 무관한 탈퇴·봇 제외 전체 가입자다 - "활성 사용자"로 오독하지 않게 registered 로 명명.
    public record NorthStarMetric(
            String metricName,
            DatePeriod period,
            int minimumDistinctCompletionDays,
            long qualifiedUserCount,
            long registeredUserCount,
            double percentage) {

        public static NorthStarMetric of(DatePeriod period,
                                         int minimumDistinctCompletionDays,
                                         long qualifiedUserCount,
                                         long registeredUserCount) {
            return new NorthStarMetric("WEEKLY_THREE_DAY_ROUTINE_COMPLETERS", period,
                    minimumDistinctCompletionDays, qualifiedUserCount, registeredUserCount,
                    AdminRetentionMetricsResponse.percentage(qualifiedUserCount, registeredUserCount));
        }
    }

    public record SegmentMetric(
            UserSegment segment,
            long userCount,
            CompletionRateMetric completionRate,
            CurrentStreakMetric currentStreak,
            RestartRateMetric restartRate) {
    }

    // COMPLETED / (COMPLETED + FAILED), KST 오늘은 day-end 미확정이라 제외함.
    public record CompletionRateMetric(
            DatePeriod period,
            long completedLogCount,
            long decidedLogCount,
            double percentage) {

        public static CompletionRateMetric of(DatePeriod period, long completedLogCount, long decidedLogCount) {
            return new CompletionRateMetric(period, completedLogCount, decidedLogCount,
                    AdminRetentionMetricsResponse.percentage(completedLogCount, decidedLogCount));
        }
    }

    // 오늘 기준 유효 current streak 합 / 현재 세그먼트 활성 사용자 수(스트릭 row가 없거나 stale이면 0).
    public record CurrentStreakMetric(
            LocalDate asOfDate,
            long currentStreakSum,
            long userCount,
            double average) {

        public static CurrentStreakMetric of(LocalDate asOfDate, long currentStreakSum, long userCount) {
            return new CurrentStreakMetric(asOfDate, currentStreakSum, userCount,
                    userCount == 0 ? 0.0 : roundOneDecimal((double) currentStreakSum / userCount));
        }
    }

    // 최근 completed_at 일자에서 완료 뒤 3개 빈 KST 일을 경험한 사용자 중 다시 완료한 사용자 비율.
    public record RestartRateMetric(
            DatePeriod period,
            int minimumEmptyDays,
            long restartedUserCount,
            long gapExperiencedUserCount,
            double percentage) {

        public static RestartRateMetric of(DatePeriod period,
                                           int minimumEmptyDays,
                                           long restartedUserCount,
                                           long gapExperiencedUserCount) {
            return new RestartRateMetric(period, minimumEmptyDays, restartedUserCount,
                    gapExperiencedUserCount,
                    AdminRetentionMetricsResponse.percentage(restartedUserCount, gapExperiencedUserCount));
        }
    }

    private static double percentage(long numerator, long denominator) {
        if (denominator == 0) {
            return 0.0;
        }
        return roundOneDecimal((double) numerator * 100.0 / denominator);
    }

    private static double roundOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
