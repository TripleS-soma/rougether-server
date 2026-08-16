package com.triples.rougether.domain.report;

import java.time.DayOfWeek;
import java.util.List;

// weekly_reports.stats_json 의 스키마. 배치가 직렬화해 저장하고 user-api 가 역직렬화해 내려준다(#286·#287 공유 계약).
// completionRate 는 0~1(소수 둘째 자리), byWeekday 는 일~토 순 7개 고정.
public record WeeklyReportStats(
        int scheduledCount,
        int completedCount,
        int failedCount,
        double completionRate,
        List<WeekdayStat> byWeekday,
        List<RoutineStat> byRoutine,
        StreakSnapshot streak) {

    public record WeekdayStat(DayOfWeek dayOfWeek, int completed, int failed) {
    }

    // lineageId 는 origin_routine_id 계보 키(coalesce(origin_routine_id, id)). title·categoryName 은 그 주에 본 최신 버전 값.
    public record RoutineStat(Long lineageId, String title, String categoryName, int completed, int failed) {
    }

    public record StreakSnapshot(int currentCount, int longestCount) {
    }
}
