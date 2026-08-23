package com.triples.rougether.adminapi.recommendation.service;

import com.triples.rougether.adminapi.recommendation.dto.AdminRecommendationMetricsResponse;
import com.triples.rougether.adminapi.recommendation.dto.AdminRecommendationMetricsResponse.WeekMetric;
import com.triples.rougether.domain.recommendation.entity.RecommendationStatus;
import com.triples.rougether.domain.recommendation.repository.RecommendationFunnelRow;
import com.triples.rougether.domain.recommendation.repository.RoutineRecommendationRepository;
import com.triples.rougether.domain.report.WeeklyReportPolicy;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 관리자 AI 조정 추천 퍼널 관측(#332). 주(생성 시각 KST 소속 일~토)마다 생성→수락/무시/만료/대기와
// 수락 효과(수락 전후 주의 계보 완료율 델타)를 집계한다. 회고 관측과 달리 진행 중 주를 최신 행으로 포함한다 -
// 추천은 그 주 배치가 만든 생성물이라 "이번 주 생성분"이 관측의 주 대상이다. 만료는 #329 결정대로
// 상태 전이 없이 ACTIVE && expires_at 경과로 판정하고, 주 경계는 WeeklyReportPolicy 를 공유한다.
@Service
@Transactional(readOnly = true)
public class AdminRecommendationMetricsService {

    public static final int DEFAULT_WEEKS = 8;
    public static final int MAX_WEEKS = 26;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RoutineRecommendationRepository routineRecommendationRepository;
    private final RoutineLogRepository routineLogRepository;
    private final Clock clock;

    public AdminRecommendationMetricsService(RoutineRecommendationRepository routineRecommendationRepository,
                                             RoutineLogRepository routineLogRepository,
                                             Clock clock) {
        this.routineRecommendationRepository = routineRecommendationRepository;
        this.routineLogRepository = routineLogRepository;
        this.clock = clock;
    }

    public AdminRecommendationMetricsResponse getMetrics(int weeks) {
        int boundedWeeks = Math.min(Math.max(weeks, 1), MAX_WEEKS);
        Instant now = clock.instant();
        LocalDate todayKst = LocalDate.ofInstant(now, KST);
        LocalDate latestWeekStart = WeeklyReportPolicy.weekStartOf(todayKst);
        LocalDate oldestWeekStart = latestWeekStart.minusWeeks(boundedWeeks - 1L);

        Map<LocalDate, List<RecommendationFunnelRow>> rowsByWeek = routineRecommendationRepository
                .findFunnelRowsCreatedAfter(oldestWeekStart.atStartOfDay(KST).toInstant()).stream()
                .collect(Collectors.groupingBy(
                        row -> WeeklyReportPolicy.weekStartOf(LocalDate.ofInstant(row.getCreatedAt(), KST))));

        List<WeekMetric> metrics = new ArrayList<>(boundedWeeks);
        for (int i = 0; i < boundedWeeks; i++) {
            LocalDate weekStart = latestWeekStart.minusWeeks(i);
            metrics.add(toWeekMetric(weekStart, rowsByWeek.getOrDefault(weekStart, List.of()), now, todayKst));
        }
        return new AdminRecommendationMetricsResponse(metrics, now);
    }

    private WeekMetric toWeekMetric(LocalDate weekStart, List<RecommendationFunnelRow> rows,
                                    Instant now, LocalDate todayKst) {
        long accepted = 0;
        long dismissed = 0;
        long expired = 0;
        long pending = 0;
        long effectMeasured = 0;
        long effectPending = 0;
        long effectUnmeasurable = 0;
        double deltaSum = 0.0;
        for (RecommendationFunnelRow row : rows) {
            if (row.getStatus() == RecommendationStatus.ACCEPTED) {
                accepted++;
                EffectMeasurement effect = measureEffect(row, todayKst);
                switch (effect.kind()) {
                    case MEASURED -> {
                        effectMeasured++;
                        deltaSum += effect.deltaPp();
                    }
                    case PENDING -> effectPending++;
                    case UNMEASURABLE -> effectUnmeasurable++;
                }
            } else if (row.getStatus() == RecommendationStatus.DISMISSED) {
                dismissed++;
            } else if (row.getExpiresAt().isAfter(now)) {
                pending++;
            } else {
                expired++;
            }
        }
        // 소수 첫째 자리로 반올림 - 개별 델타 합산 오차가 화면 숫자를 흔들지 않게
        Double avgDeltaPp = effectMeasured == 0 ? null : Math.round(deltaSum / effectMeasured * 10.0) / 10.0;
        return WeekMetric.of(weekStart, WeeklyReportPolicy.weekEndOf(weekStart), weekStart.equals(
                        WeeklyReportPolicy.weekStartOf(todayKst)),
                rows.size(), accepted, dismissed, expired, pending,
                effectMeasured, effectPending, effectUnmeasurable, avgDeltaPp);
    }

    private enum EffectKind { MEASURED, PENDING, UNMEASURABLE }

    private record EffectMeasurement(EffectKind kind, double deltaPp) {
        static EffectMeasurement measured(double deltaPp) {
            return new EffectMeasurement(EffectKind.MEASURED, deltaPp);
        }

        static final EffectMeasurement PENDING = new EffectMeasurement(EffectKind.PENDING, 0.0);
        static final EffectMeasurement UNMEASURABLE = new EffectMeasurement(EffectKind.UNMEASURABLE, 0.0);
    }

    // 수락 효과 = 수락 주(옛·새 스케줄이 섞이는 주라 제외) 직전 주 대비 다음 주의 계보 완료율 델타(pp).
    // 완료율 소싱은 회고·추천 룰과 같은 COMPLETED/FAILED log 기준이고, 다음 주가 아직 안 끝났으면 측정 대기,
    // 어느 한쪽 주에 log 가 없거나 계보가 통째로 삭제됐으면 측정 불가로 센다.
    private EffectMeasurement measureEffect(RecommendationFunnelRow row, LocalDate todayKst) {
        LocalDate actedWeekStart = WeeklyReportPolicy.weekStartOf(LocalDate.ofInstant(row.getActedAt(), KST));
        LocalDate afterStart = actedWeekStart.plusDays(WeeklyReportPolicy.WEEK_LENGTH_DAYS);
        LocalDate afterEnd = WeeklyReportPolicy.weekEndOf(afterStart);
        if (!todayKst.isAfter(afterEnd)) {
            return EffectMeasurement.PENDING;
        }
        LocalDate beforeStart = actedWeekStart.minusDays(WeeklyReportPolicy.WEEK_LENGTH_DAYS);
        List<RoutineLog> logs = routineLogRepository.findLineageAliveLogsInPeriodByOrigin(
                row.getUserId(), row.getOriginRoutineId(), beforeStart, afterEnd,
                WeeklyReportPolicy.COUNTED_LOG_STATUSES);
        Rate before = rateOf(logs, beforeStart, actedWeekStart.minusDays(1));
        Rate after = rateOf(logs, afterStart, afterEnd);
        if (before.total() == 0 || after.total() == 0) {
            return EffectMeasurement.UNMEASURABLE;
        }
        return EffectMeasurement.measured((after.value() - before.value()) * 100.0);
    }

    private record Rate(long completed, long total) {
        double value() {
            return (double) completed / total;
        }
    }

    private static Rate rateOf(List<RoutineLog> logs, LocalDate from, LocalDate to) {
        long completed = 0;
        long total = 0;
        for (RoutineLog log : logs) {
            if (log.getRoutineDate().isBefore(from) || log.getRoutineDate().isAfter(to)) {
                continue;
            }
            total++;
            if (log.getStatus() == RoutineLogStatus.COMPLETED) {
                completed++;
            }
        }
        return new Rate(completed, total);
    }
}
