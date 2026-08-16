package com.triples.rougether.batch.weeklyreport;

import com.triples.rougether.domain.report.WeeklyReportStats;
import com.triples.rougether.domain.report.WeeklyReportStats.RoutineStat;
import com.triples.rougether.domain.report.WeeklyReportStats.StreakSnapshot;
import com.triples.rougether.domain.report.WeeklyReportStats.WeekdayStat;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.Streak;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

// 한 사용자의 주간 routine_logs(COMPLETED/FAILED, 삭제 루틴 제외됨)를 통계로 접는다. 순수 계산이라 상태 없음.
// 루틴은 origin_routine_id 계보 단위로 병합하고, 표시명은 그 주에 본 가장 새 버전(id 최대)의 title·카테고리명을 쓴다.
@Component
public class WeeklyStatsAggregator {

    // 주 경계가 일~토라 요일 순서도 일요일부터 고정
    static final List<DayOfWeek> WEEKDAY_ORDER = List.of(
            DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);

    public WeeklyReportStats aggregate(List<RoutineLog> logs, Optional<Streak> streak) {
        int completed = 0;
        int failed = 0;
        Map<DayOfWeek, int[]> byWeekday = new EnumMap<>(DayOfWeek.class);
        WEEKDAY_ORDER.forEach(d -> byWeekday.put(d, new int[2]));
        Map<Long, RoutineAccumulator> byLineage = new LinkedHashMap<>();

        for (RoutineLog log : logs) {
            boolean isCompleted = log.getStatus() == RoutineLogStatus.COMPLETED;
            boolean isFailed = log.getStatus() == RoutineLogStatus.FAILED;
            if (!isCompleted && !isFailed) {
                continue; // PENDING 등은 집계 대상 아님(현재 쓰는 경로 없음)
            }
            if (isCompleted) {
                completed++;
            } else {
                failed++;
            }
            int[] dayCounts = byWeekday.get(log.getRoutineDate().getDayOfWeek());
            dayCounts[isCompleted ? 0 : 1]++;

            Routine routine = log.getRoutine();
            Long lineageId = routine.getOriginRoutineId() != null ? routine.getOriginRoutineId() : routine.getId();
            byLineage.computeIfAbsent(lineageId, RoutineAccumulator::new).add(routine, isCompleted);
        }

        int scheduled = completed + failed;
        List<WeekdayStat> weekdayStats = WEEKDAY_ORDER.stream()
                .map(d -> new WeekdayStat(d, byWeekday.get(d)[0], byWeekday.get(d)[1]))
                .toList();
        List<RoutineStat> routineStats = byLineage.values().stream().map(RoutineAccumulator::toStat).toList();
        StreakSnapshot streakSnapshot = streak
                .map(s -> new StreakSnapshot(s.getCurrentCount(), s.getLongestCount()))
                .orElse(new StreakSnapshot(0, 0));
        return new WeeklyReportStats(scheduled, completed, failed, completionRate(completed, scheduled),
                weekdayStats, routineStats, streakSnapshot);
    }

    static double completionRate(int completed, int scheduled) {
        if (scheduled == 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(completed)
                .divide(BigDecimal.valueOf(scheduled), 2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static final class RoutineAccumulator {
        private final Long lineageId;
        private Routine latestVersion;
        private int completed;
        private int failed;

        private RoutineAccumulator(Long lineageId) {
            this.lineageId = lineageId;
        }

        private void add(Routine routine, boolean isCompleted) {
            if (latestVersion == null || routine.getId() > latestVersion.getId()) {
                latestVersion = routine;
            }
            if (isCompleted) {
                completed++;
            } else {
                failed++;
            }
        }

        private RoutineStat toStat() {
            Category category = latestVersion.getCategory();
            String categoryName = category != null && category.getDeletedAt() == null ? category.getName() : null;
            return new RoutineStat(lineageId, latestVersion.getTitle(), categoryName, completed, failed);
        }
    }
}
