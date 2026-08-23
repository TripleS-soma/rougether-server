package com.triples.rougether.adminapi.recommendation.service;

import com.triples.rougether.adminapi.recommendation.dto.AdminRecommendationMetricsResponse;
import com.triples.rougether.adminapi.recommendation.dto.AdminRecommendationMetricsResponse.WeekMetric;
import com.triples.rougether.domain.recommendation.entity.RecommendationStatus;
import com.triples.rougether.domain.recommendation.repository.RecommendationFunnelRow;
import com.triples.rougether.domain.recommendation.repository.RoutineRecommendationRepository;
import com.triples.rougether.domain.report.WeeklyReportPolicy;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.repository.LineageAliveVersion;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 관리자 AI 조정 추천 퍼널 관측(#332). 주(생성 시각 KST 소속 일~토)마다 생성→수락/무시/만료/대기와
// 수락 효과(수락 전후 주의 완료율 델타)를 집계한다. 회고 관측과 달리 진행 중 주를 최신 행으로 포함한다 -
// 추천은 그 주 배치가 만든 생성물이라 "이번 주 생성분"이 관측의 주 대상이다. 만료는 #329 결정대로 상태 전이
// 없이 lazy 판정하며, 기한 경과뿐 아니라 루틴 삭제·선행 스케줄 수정으로 무효가 되어 사용자 목록에서 빠진
// ACTIVE 건도 만료와 같은 무반응 종결로 센다(#333 리뷰 — RecommendationQueryService 의 lazy 제외와 정합).
// 주 경계는 WeeklyReportPolicy 를 공유한다.
@Service
@Transactional(readOnly = true)
public class AdminRecommendationMetricsService {

    public static final int DEFAULT_WEEKS = 8;
    public static final int MAX_WEEKS = 26;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RoutineRecommendationRepository routineRecommendationRepository;
    private final RoutineRepository routineRepository;
    private final RoutineLogRepository routineLogRepository;
    private final Clock clock;

    public AdminRecommendationMetricsService(RoutineRecommendationRepository routineRecommendationRepository,
                                             RoutineRepository routineRepository,
                                             RoutineLogRepository routineLogRepository,
                                             Clock clock) {
        this.routineRecommendationRepository = routineRecommendationRepository;
        this.routineRepository = routineRepository;
        this.routineLogRepository = routineLogRepository;
        this.clock = clock;
    }

    public AdminRecommendationMetricsResponse getMetrics(int weeks) {
        int boundedWeeks = Math.min(Math.max(weeks, 1), MAX_WEEKS);
        Instant now = clock.instant();
        LocalDate todayKst = LocalDate.ofInstant(now, KST);
        LocalDate latestWeekStart = WeeklyReportPolicy.weekStartOf(todayKst);
        LocalDate oldestWeekStart = latestWeekStart.minusWeeks(boundedWeeks - 1L);

        List<RecommendationFunnelRow> rows = routineRecommendationRepository
                .findFunnelRowsCreatedAfter(oldestWeekStart.atStartOfDay(KST).toInstant());
        Map<LocalDate, List<RecommendationFunnelRow>> rowsByWeek = rows.stream()
                .collect(Collectors.groupingBy(
                        row -> WeeklyReportPolicy.weekStartOf(LocalDate.ofInstant(row.getCreatedAt(), KST))));
        Map<Long, Set<Long>> aliveByOrigin = loadAliveVersions(rows, now);

        List<WeekMetric> metrics = new ArrayList<>(boundedWeeks);
        for (int i = 0; i < boundedWeeks; i++) {
            LocalDate weekStart = latestWeekStart.minusWeeks(i);
            metrics.add(toWeekMetric(weekStart, rowsByWeek.getOrDefault(weekStart, List.of()),
                    now, todayKst, aliveByOrigin));
        }
        return new AdminRecommendationMetricsResponse(metrics, now);
    }

    // 대기 후보(ACTIVE·기한 내)의 계보 현재 버전을 일괄 조회함 - 무효(삭제·stale) 판정 재료
    private Map<Long, Set<Long>> loadAliveVersions(List<RecommendationFunnelRow> rows, Instant now) {
        Set<Long> originKeys = rows.stream()
                .filter(row -> row.getStatus() == RecommendationStatus.ACTIVE && row.getExpiresAt().isAfter(now))
                .map(RecommendationFunnelRow::getOriginRoutineId)
                .collect(Collectors.toSet());
        if (originKeys.isEmpty()) {
            return Map.of();
        }
        return routineRepository.findAliveVersionsByLineages(originKeys).stream()
                .collect(Collectors.groupingBy(LineageAliveVersion::getOriginKey,
                        Collectors.mapping(LineageAliveVersion::getRoutineId, Collectors.toSet())));
    }

    private WeekMetric toWeekMetric(LocalDate weekStart, List<RecommendationFunnelRow> rows,
                                    Instant now, LocalDate todayKst, Map<Long, Set<Long>> aliveByOrigin) {
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
            } else if (!row.getExpiresAt().isAfter(now)) {
                expired++;
            } else if (isStillActionable(row, aliveByOrigin)) {
                pending++;
            } else {
                // 기한은 남았지만 루틴 삭제·선행 수정으로 무효 → 사용자 목록에서 이미 빠졌으니 만료와 같은 무반응 종결
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

    // 대기 = 계보의 현재 버전이 생성 시점 대상 버전 그대로일 때만(사용자 목록 lazy 필터와 같은 조건)
    private static boolean isStillActionable(RecommendationFunnelRow row, Map<Long, Set<Long>> aliveByOrigin) {
        return aliveByOrigin.getOrDefault(row.getOriginRoutineId(), Set.of()).contains(row.getRoutineId());
    }

    private enum EffectKind { MEASURED, PENDING, UNMEASURABLE }

    private record EffectMeasurement(EffectKind kind, double deltaPp) {
        static EffectMeasurement measured(double deltaPp) {
            return new EffectMeasurement(EffectKind.MEASURED, deltaPp);
        }

        static final EffectMeasurement PENDING = new EffectMeasurement(EffectKind.PENDING, 0.0);
        static final EffectMeasurement UNMEASURABLE = new EffectMeasurement(EffectKind.UNMEASURABLE, 0.0);
    }

    // 수락 효과 = 수락 주(옛·새 스케줄이 섞이는 주라 제외) 직전 주 대비 다음 주의 완료율 델타(pp).
    // 직전 주는 수락 전 행태라 계보 전체 log 를 쓰고, 다음 주는 spec 의 효과 측정 조인 키대로 수락 적용 버전
    // (applied_routine_id)의 log 만 귀속한다(#333 리뷰) — 수락 뒤 사용자가 또 수정해 재분기한 버전의 log 가
    // 이 추천의 효과로 섞이지 않게. 완료율 소싱은 회고·추천 룰과 같은 COMPLETED/FAILED log 기준이고,
    // 다음 주가 아직 안 끝났으면 측정 대기, 어느 한쪽 주에 귀속 log 가 없거나 계보가 통째로 삭제됐으면 측정 불가.
    private EffectMeasurement measureEffect(RecommendationFunnelRow row, LocalDate todayKst) {
        LocalDate actedWeekStart = WeeklyReportPolicy.weekStartOf(LocalDate.ofInstant(row.getActedAt(), KST));
        LocalDate afterStart = actedWeekStart.plusDays(WeeklyReportPolicy.WEEK_LENGTH_DAYS);
        LocalDate afterEnd = WeeklyReportPolicy.weekEndOf(afterStart);
        if (!todayKst.isAfter(afterEnd)) {
            return EffectMeasurement.PENDING;
        }
        if (row.getAppliedRoutineId() == null) {
            return EffectMeasurement.UNMEASURABLE;
        }
        LocalDate beforeStart = actedWeekStart.minusDays(WeeklyReportPolicy.WEEK_LENGTH_DAYS);
        List<RoutineLog> logs = routineLogRepository.findLineageAliveLogsInPeriodByOrigin(
                row.getUserId(), row.getOriginRoutineId(), beforeStart, afterEnd,
                WeeklyReportPolicy.COUNTED_LOG_STATUSES);
        Rate before = rateOf(logs, beforeStart, actedWeekStart.minusDays(1), null);
        Rate after = rateOf(logs, afterStart, afterEnd, row.getAppliedRoutineId());
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

    // onlyRoutineId 가 있으면 그 버전의 log 만 센다(효과 귀속). routine 은 lazy 지만 id 는 proxy 초기화 없이 읽힘
    private static Rate rateOf(List<RoutineLog> logs, LocalDate from, LocalDate to, Long onlyRoutineId) {
        long completed = 0;
        long total = 0;
        for (RoutineLog log : logs) {
            if (log.getRoutineDate().isBefore(from) || log.getRoutineDate().isAfter(to)) {
                continue;
            }
            if (onlyRoutineId != null && !onlyRoutineId.equals(log.getRoutine().getId())) {
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
