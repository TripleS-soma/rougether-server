package com.triples.rougether.adminapi.report.service;

import com.triples.rougether.adminapi.report.dto.AdminWeeklyReportMetricsResponse;
import com.triples.rougether.adminapi.report.dto.AdminWeeklyReportMetricsResponse.WeekMetric;
import com.triples.rougether.domain.report.WeeklyReportPolicy;
import com.triples.rougether.domain.report.entity.WeeklyReportStatus;
import com.triples.rougether.domain.report.repository.WeeklyReportRepository;
import com.triples.rougether.domain.report.repository.WeeklyReportStatusCount;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 관리자 AI 주간 회고 관측. 주(일~토 KST)마다 회고 대상 사용자 수 대비 생성·FALLBACK·누락 건수를 계산한다.
// 대상 사용자 조건·주 경계·배치 시각은 domain WeeklyReportPolicy(batch 와 공유)를 따르고,
// 대상 수는 조회 시점이 아니라 그 주 배치가 끝난 시각(cutoff) 기준으로 되짚어 배치 이후 변동을 배제한다.
@Service
@Transactional(readOnly = true)
public class AdminWeeklyReportMetricsService {

    public static final int DEFAULT_WEEKS = 8;
    public static final int MAX_WEEKS = 26;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final WeeklyReportRepository weeklyReportRepository;
    private final RoutineLogRepository routineLogRepository;
    private final Clock clock;

    public AdminWeeklyReportMetricsService(WeeklyReportRepository weeklyReportRepository,
                                           RoutineLogRepository routineLogRepository,
                                           Clock clock) {
        this.weeklyReportRepository = weeklyReportRepository;
        this.routineLogRepository = routineLogRepository;
        this.clock = clock;
    }

    public AdminWeeklyReportMetricsResponse getMetrics(int weeks) {
        int boundedWeeks = Math.min(Math.max(weeks, 1), MAX_WEEKS);
        Instant now = clock.instant();
        LocalDate latestWeekStart = WeeklyReportPolicy.latestCompletedWeekStart(LocalDate.ofInstant(now, KST));
        LocalDate oldestWeekStart = latestWeekStart.minusWeeks(boundedWeeks - 1L);

        Map<LocalDate, List<WeeklyReportStatusCount>> countsByWeek =
                weeklyReportRepository.countByWeekStatusModelBetween(oldestWeekStart, latestWeekStart).stream()
                        .collect(Collectors.groupingBy(WeeklyReportStatusCount::getWeekStartDate));

        List<WeekMetric> metrics = new ArrayList<>(boundedWeeks);
        for (int i = 0; i < boundedWeeks; i++) {
            LocalDate weekStart = latestWeekStart.minusWeeks(i);
            metrics.add(toWeekMetric(weekStart, countsByWeek.getOrDefault(weekStart, List.of()), now));
        }
        return new AdminWeeklyReportMetricsResponse(metrics, now);
    }

    private WeekMetric toWeekMetric(LocalDate weekStart, List<WeeklyReportStatusCount> counts, Instant now) {
        LocalDate weekEnd = WeeklyReportPolicy.weekEndOf(weekStart);
        long generated = sumByStatus(counts, WeeklyReportStatus.GENERATED);
        long fallback = sumByStatus(counts, WeeklyReportStatus.FALLBACK);
        List<String> models = counts.stream()
                .map(WeeklyReportStatusCount::getModel)
                .filter(model -> model != null && !model.isBlank())
                .collect(Collectors.toCollection(TreeSet::new))
                .stream().toList();
        Instant lastGeneratedAt = counts.stream()
                .map(WeeklyReportStatusCount::getLastGeneratedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
        Instant firstTriggerAt = firstTriggerAt(weekEnd);
        // 배치가 실제로 돈 뒤(마지막 생성 시각) 상태를 기준으로 대상 수를 되짚는다. 아직 생성이 없으면 첫 배치 예정 시각.
        Instant cutoff = lastGeneratedAt != null ? lastGeneratedAt : firstTriggerAt;
        long eligible = routineLogRepository.countUsersWithLogsInPeriodAsOf(
                weekStart, weekEnd, WeeklyReportPolicy.COUNTED_LOG_STATUSES, cutoff);
        boolean beforeFirstTrigger = now.isBefore(firstTriggerAt);
        return WeekMetric.of(weekStart, weekEnd, beforeFirstTrigger, eligible, generated, fallback,
                models, lastGeneratedAt);
    }

    private static long sumByStatus(List<WeeklyReportStatusCount> counts, WeeklyReportStatus status) {
        return counts.stream()
                .filter(count -> count.getStatus() == status)
                .mapToLong(WeeklyReportStatusCount::getReportCount)
                .sum();
    }

    public static Instant firstTriggerAt(LocalDate weekEnd) {
        return LocalDateTime.of(weekEnd.plusDays(1), WeeklyReportPolicy.FIRST_TRIGGER_TIME)
                .atZone(KST).toInstant();
    }
}
