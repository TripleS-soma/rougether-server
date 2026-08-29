package com.triples.rougether.adminapi.recommendation.service;

import com.triples.rougether.adminapi.recommendation.dto.AdminRecommendationMetricsResponse;
import com.triples.rougether.adminapi.recommendation.dto.AdminRecommendationMetricsResponse.VariantMetric;
import com.triples.rougether.adminapi.recommendation.dto.AdminRecommendationMetricsResponse.VariantWeekMetric;
import com.triples.rougether.adminapi.recommendation.dto.AdminRecommendationMetricsResponse.WeekMetric;
import com.triples.rougether.domain.recommendation.RecommendationExperimentPolicy;
import com.triples.rougether.domain.recommendation.entity.RecommendationExperimentVariant;
import com.triples.rougether.domain.recommendation.entity.RecommendationSource;
import com.triples.rougether.domain.recommendation.entity.RecommendationStatus;
import com.triples.rougether.domain.recommendation.entity.RecommendationType;
import com.triples.rougether.domain.recommendation.repository.RecommendationExperimentEligibilityRepository;
import com.triples.rougether.domain.recommendation.repository.RecommendationExperimentEligibilityRow;
import com.triples.rougether.domain.recommendation.repository.RecommendationFunnelRow;
import com.triples.rougether.domain.recommendation.repository.RoutineRecommendationRepository;
import com.triples.rougether.domain.report.WeeklyReportPolicy;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.repository.LineageAliveVersion;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RecommendationExperimentCompletionRow;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
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
    private final RecommendationExperimentEligibilityRepository eligibilityRepository;
    private final RoutineRepository routineRepository;
    private final RoutineLogRepository routineLogRepository;
    private final Clock clock;

    public AdminRecommendationMetricsService(RoutineRecommendationRepository routineRecommendationRepository,
                                             RecommendationExperimentEligibilityRepository eligibilityRepository,
                                             RoutineRepository routineRepository,
                                             RoutineLogRepository routineLogRepository,
                                             Clock clock) {
        this.routineRecommendationRepository = routineRecommendationRepository;
        this.eligibilityRepository = eligibilityRepository;
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

        Instant oldestWeekStartInstant = oldestWeekStart.atStartOfDay(KST).toInstant();
        List<RecommendationFunnelRow> rows = routineRecommendationRepository
                .findFunnelRowsCreatedAfter(oldestWeekStartInstant);
        Map<LocalDate, List<RecommendationFunnelRow>> rowsByWeek = rows.stream()
                .collect(Collectors.groupingBy(
                        row -> WeeklyReportPolicy.weekStartOf(LocalDate.ofInstant(row.getCreatedAt(), KST))));
        List<RecommendationFunnelRow> experimentRows = routineRecommendationRepository
                .findExperimentFunnelRowsCreatedAfter(oldestWeekStartInstant,
                        RecommendationType.ADJUST_DAYS, RecommendationSource.RULE);
        Map<LocalDate, List<RecommendationFunnelRow>> experimentRowsByWeek = experimentRows.stream()
                .collect(Collectors.groupingBy(
                        row -> WeeklyReportPolicy.weekStartOf(LocalDate.ofInstant(row.getCreatedAt(), KST))));
        Map<Long, Set<Long>> aliveByOrigin = loadAliveVersions(rows, now);
        List<RecommendationExperimentEligibilityRow> eligibilityRows = eligibilityRepository.findActiveHumanRows(
                RecommendationExperimentPolicy.ROUTINE_ADJUSTMENT_V1, oldestWeekStart, latestWeekStart);
        Map<LocalDate, List<RecommendationExperimentEligibilityRow>> eligibilityByWeek = eligibilityRows.stream()
                .collect(Collectors.groupingBy(RecommendationExperimentEligibilityRow::getCohortWeekStart));
        Set<Long> experimentUserIds = eligibilityRows.stream()
                .map(RecommendationExperimentEligibilityRow::getUserId)
                .collect(Collectors.toSet());
        Map<Long, List<RecommendationExperimentCompletionRow>> completionRowsByUser = loadCompletionRows(
                experimentUserIds, oldestWeekStart, latestWeekStart);

        List<WeekMetric> metrics = new ArrayList<>(boundedWeeks);
        List<VariantWeekMetric> variantMetrics = new ArrayList<>(boundedWeeks);
        for (int i = 0; i < boundedWeeks; i++) {
            LocalDate weekStart = latestWeekStart.minusWeeks(i);
            metrics.add(toWeekMetric(weekStart, rowsByWeek.getOrDefault(weekStart, List.of()),
                    now, todayKst, aliveByOrigin));
            variantMetrics.add(toVariantWeekMetric(weekStart,
                    eligibilityByWeek.getOrDefault(weekStart, List.of()),
                    experimentRowsByWeek.getOrDefault(weekStart, List.of()), todayKst, completionRowsByUser));
        }
        return new AdminRecommendationMetricsResponse(metrics, variantMetrics, now);
    }

    private Map<Long, List<RecommendationExperimentCompletionRow>> loadCompletionRows(
            Set<Long> userIds, LocalDate oldestWeekStart, LocalDate latestWeekStart) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        LocalDate fromDate = oldestWeekStart.minusWeeks(1);
        LocalDate toDate = WeeklyReportPolicy.weekEndOf(latestWeekStart.plusWeeks(1));
        return routineLogRepository.findExperimentCompletionRows(userIds, fromDate, toDate,
                        WeeklyReportPolicy.COUNTED_LOG_STATUSES).stream()
                .collect(Collectors.groupingBy(RecommendationExperimentCompletionRow::getUserId));
    }

    private VariantWeekMetric toVariantWeekMetric(
            LocalDate weekStart,
            List<RecommendationExperimentEligibilityRow> eligibilityRows,
            List<RecommendationFunnelRow> recommendationRows,
            LocalDate todayKst,
            Map<Long, List<RecommendationExperimentCompletionRow>> completionRowsByUser) {
        Map<RecommendationExperimentVariant, Set<Long>> targetsByVariant = new EnumMap<>(
                RecommendationExperimentVariant.class);
        for (RecommendationExperimentVariant variant : RecommendationExperimentVariant.values()) {
            targetsByVariant.put(variant, new HashSet<>());
        }
        for (RecommendationExperimentEligibilityRow row : eligibilityRows) {
            targetsByVariant.get(row.getVariant()).add(row.getUserId());
        }
        VariantMetric control = toVariantMetric(RecommendationExperimentVariant.CONTROL,
                targetsByVariant.get(RecommendationExperimentVariant.CONTROL), recommendationRows,
                weekStart, todayKst, completionRowsByUser);
        VariantMetric treatment = toVariantMetric(RecommendationExperimentVariant.TREATMENT,
                targetsByVariant.get(RecommendationExperimentVariant.TREATMENT), recommendationRows,
                weekStart, todayKst, completionRowsByUser);
        Double lift = control.completionDeltaPp() == null || treatment.completionDeltaPp() == null
                ? null : round(treatment.completionDeltaPp() - control.completionDeltaPp());
        return new VariantWeekMetric(weekStart, WeeklyReportPolicy.weekEndOf(weekStart), control, treatment, lift);
    }

    private VariantMetric toVariantMetric(
            RecommendationExperimentVariant variant,
            Set<Long> targetUserIds,
            List<RecommendationFunnelRow> recommendationRows,
            LocalDate weekStart,
            LocalDate todayKst,
            Map<Long, List<RecommendationExperimentCompletionRow>> completionRowsByUser) {
        Set<Long> generatedUserIds = recommendationRows.stream()
                .map(RecommendationFunnelRow::getUserId)
                .filter(targetUserIds::contains)
                .collect(Collectors.toSet());
        Set<Long> acceptedUserIds = recommendationRows.stream()
                .filter(row -> row.getStatus() == RecommendationStatus.ACCEPTED)
                .map(RecommendationFunnelRow::getUserId)
                .filter(targetUserIds::contains)
                .collect(Collectors.toSet());

        LocalDate nextWeekStart = weekStart.plusWeeks(1);
        LocalDate nextWeekEnd = WeeklyReportPolicy.weekEndOf(nextWeekStart);
        if (!todayKst.isAfter(nextWeekEnd.plusDays(1))) {
            return VariantMetric.of(variant, targetUserIds.size(), generatedUserIds.size(), acceptedUserIds.size(),
                    0, targetUserIds.size(), 0, null, null, null);
        }

        LocalDate baselineStart = weekStart.minusWeeks(1);
        LocalDate baselineEnd = WeeklyReportPolicy.weekEndOf(baselineStart);
        long measuredUsers = 0;
        long unmeasurableUsers = 0;
        long baselineCompleted = 0;
        long baselineTotal = 0;
        long nextCompleted = 0;
        long nextTotal = 0;
        for (Long userId : targetUserIds) {
            List<RecommendationExperimentCompletionRow> userRows = completionRowsByUser.getOrDefault(userId, List.of());
            ExperimentRate baseline = experimentRateOf(userRows, baselineStart, baselineEnd);
            ExperimentRate next = experimentRateOf(userRows, nextWeekStart, nextWeekEnd);
            if (baseline.total() == 0 || next.total() == 0) {
                unmeasurableUsers++;
                continue;
            }
            measuredUsers++;
            baselineCompleted += baseline.completed();
            baselineTotal += baseline.total();
            nextCompleted += next.completed();
            nextTotal += next.total();
        }
        Double baselineRate = baselineTotal == 0 ? null : round(baselineCompleted * 100.0 / baselineTotal);
        Double nextRate = nextTotal == 0 ? null : round(nextCompleted * 100.0 / nextTotal);
        Double delta = baselineRate == null || nextRate == null ? null : round(nextRate - baselineRate);
        return VariantMetric.of(variant, targetUserIds.size(), generatedUserIds.size(), acceptedUserIds.size(),
                measuredUsers, 0, unmeasurableUsers, baselineRate, nextRate, delta);
    }

    private record ExperimentRate(long completed, long total) {
    }

    private static ExperimentRate experimentRateOf(Collection<RecommendationExperimentCompletionRow> rows,
                                                   LocalDate from, LocalDate to) {
        long completed = 0;
        long total = 0;
        for (RecommendationExperimentCompletionRow row : rows) {
            if (row.getRoutineDate().isBefore(from) || row.getRoutineDate().isAfter(to)) {
                continue;
            }
            total++;
            if (row.getStatus() == RoutineLogStatus.COMPLETED) {
                completed++;
            }
        }
        return new ExperimentRate(completed, total);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
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

    // 대기 = 계보의 현재 버전이 생성 시점 대상 버전 그대로일 때만(사용자 목록 lazy 필터의 버전 판정과 동일).
    // 목록이 추가로 거르는 proposal JSON 파손 건은 여기선 확인하지 않는다 - 파싱 계약이 user-api DTO 소관인 데다
    // proposal 은 배치가 만드는 값이라 파손은 데이터 이상 신호이고, 그 잔차는 관측에서 pending 으로 남는 걸 감수한다.
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
        // 토요일 FAILED log 는 day-end 배치가 자정 직후 확정하므로 주가 끝난 당일(일요일)엔 실패가 빠진 채
        // 부풀 수 있다(#333 리뷰). 배치 확정 여유로 하루를 더 두고 월요일(KST)부터 측정한다 - admin 이
        // batch job 메타데이터를 직접 대조하는 결합 대신 관측 지표에 충분한 근사를 택함(지표는 조회 시점 재계산).
        if (!todayKst.isAfter(afterEnd.plusDays(1))) {
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
