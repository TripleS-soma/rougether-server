package com.triples.rougether.adminapi.retention.service;

import com.triples.rougether.adminapi.retention.dto.AdminRetentionMetricsResponse;
import com.triples.rougether.adminapi.retention.dto.AdminRetentionMetricsResponse.CompletionRateMetric;
import com.triples.rougether.adminapi.retention.dto.AdminRetentionMetricsResponse.CurrentStreakMetric;
import com.triples.rougether.adminapi.retention.dto.AdminRetentionMetricsResponse.DatePeriod;
import com.triples.rougether.adminapi.retention.dto.AdminRetentionMetricsResponse.NorthStarMetric;
import com.triples.rougether.adminapi.retention.dto.AdminRetentionMetricsResponse.RestartRateMetric;
import com.triples.rougether.adminapi.retention.dto.AdminRetentionMetricsResponse.RetentionCohortMetric;
import com.triples.rougether.adminapi.retention.dto.AdminRetentionMetricsResponse.RetentionMetric;
import com.triples.rougether.adminapi.retention.dto.AdminRetentionMetricsResponse.RetentionMetrics;
import com.triples.rougether.adminapi.retention.dto.AdminRetentionMetricsResponse.RetentionPoint;
import com.triples.rougether.adminapi.retention.dto.AdminRetentionMetricsResponse.SegmentMetric;
import com.triples.rougether.adminapi.retention.dto.AdminRetentionMetricsResponse.UserSegment;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.retention.repository.AdminRetentionMetricRepository;
import com.triples.rougether.domain.retention.repository.AdminRetentionMetricRepository.ActiveUserMetricRow;
import com.triples.rougether.domain.retention.repository.AdminRetentionMetricRepository.CompletionEventMetricRow;
import com.triples.rougether.domain.retention.repository.AdminRetentionMetricRepository.DailyActivityMetricRow;
import com.triples.rougether.domain.retention.repository.AdminRetentionMetricRepository.RoutineOutcomeMetricRow;
import com.triples.rougether.domain.retention.repository.AdminRetentionMetricRepository.StreakMetricRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminRetentionMetricsService {

    public static final int DEFAULT_COHORT_DAYS = 35;
    public static final int MAX_COHORT_DAYS = 90;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int NORTH_STAR_DAYS = 7;
    private static final int NORTH_STAR_MINIMUM_COMPLETION_DAYS = 3;
    private static final int COMPLETION_RATE_DAYS = 30;
    private static final int RESTART_OBSERVATION_DAYS = 30;
    private static final int RESTART_EMPTY_DAYS = 3;

    private final AdminRetentionMetricRepository metricRepository;
    private final Clock clock;

    public AdminRetentionMetricsService(AdminRetentionMetricRepository metricRepository, Clock clock) {
        this.metricRepository = metricRepository;
        this.clock = clock;
    }

    public AdminRetentionMetricsResponse getMetrics(int cohortDays) {
        int boundedCohortDays = Math.min(Math.max(cohortDays, 1), MAX_COHORT_DAYS);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, KST);
        LocalDate cohortFrom = today.minusDays(boundedCohortDays - 1L);
        DatePeriod cohortPeriod = DatePeriod.inclusive(cohortFrom, today);

        List<ActiveUserSnapshot> activeUsers = metricRepository.findActiveUsers().stream()
                .map(row -> new ActiveUserSnapshot(row.getUserId(), LocalDate.ofInstant(row.getCreatedAt(), KST)))
                .toList();
        Set<Long> activeUserIds = activeUsers.stream()
                .map(ActiveUserSnapshot::userId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        LocalDate activityFrom = cohortFrom.isBefore(today) ? cohortFrom.plusDays(1) : today;
        Map<Long, Set<LocalDate>> activityDatesByUser = groupActivityDates(
                metricRepository.findActivitiesBetween(
                        activityFrom, today, cohortFrom.atStartOfDay(KST).toInstant()),
                activeUserIds);
        List<ActiveUserSnapshot> cohortUsers = activeUsers.stream()
                .filter(user -> !user.signupDate().isBefore(cohortFrom) && !user.signupDate().isAfter(today))
                .toList();
        List<RetentionCohortMetric> retentionCohorts = toRetentionCohorts(
                cohortUsers, activityDatesByUser, today);
        RetentionMetrics retention = new RetentionMetrics(
                "AUTHENTICATED_REQUEST_EXACT_DAY",
                aggregateRetention(1, cohortUsers, activityDatesByUser, today),
                aggregateRetention(7, cohortUsers, activityDatesByUser, today),
                aggregateRetention(30, cohortUsers, activityDatesByUser, today));

        LocalDate restartFrom = today.minusDays(RESTART_OBSERVATION_DAYS - 1L);
        DatePeriod restartPeriod = DatePeriod.inclusive(restartFrom, today);
        Map<Long, Set<LocalDate>> completionDatesByUser = groupCompletionDates(
                metricRepository.findCompletionEvents(restartFrom.atStartOfDay(KST).toInstant(), now),
                activeUserIds, restartFrom, today);

        LocalDate northStarFrom = today.minusDays(NORTH_STAR_DAYS - 1L);
        long northStarQualified = activeUserIds.stream()
                .filter(userId -> completionDatesByUser.getOrDefault(userId, Set.of()).stream()
                        .filter(date -> !date.isBefore(northStarFrom) && !date.isAfter(today))
                        .limit(NORTH_STAR_MINIMUM_COMPLETION_DAYS)
                        .count() >= NORTH_STAR_MINIMUM_COMPLETION_DAYS)
                .count();
        NorthStarMetric northStar = NorthStarMetric.of(
                DatePeriod.inclusive(northStarFrom, today),
                NORTH_STAR_MINIMUM_COMPLETION_DAYS,
                northStarQualified,
                activeUserIds.size());

        LocalDate completionFrom = today.minusDays(COMPLETION_RATE_DAYS);
        LocalDate completionTo = today.minusDays(1);
        DatePeriod completionPeriod = DatePeriod.inclusive(completionFrom, completionTo);
        Map<Long, Outcome> outcomesByUser = metricRepository
                .findRoutineOutcomesBetween(completionFrom, completionTo).stream()
                .filter(row -> activeUserIds.contains(row.getUserId()))
                .collect(Collectors.toMap(
                        RoutineOutcomeMetricRow::getUserId,
                        row -> new Outcome(row.getCompletedCount(), row.getTotalCount()),
                        Outcome::plus));
        Map<Long, Integer> currentStreakByUser = currentStreaks(
                metricRepository.findStreaksForActiveUsers(), activeUserIds, today);
        Map<Long, RestartState> restartByUser = restartStates(completionDatesByUser, activeUserIds, today);

        Set<Long> sharedUserIds = metricRepository.findSharedUserIds(HouseMemberStatus.ACTIVE).stream()
                .filter(activeUserIds::contains)
                .collect(Collectors.toSet());
        Set<Long> personalUserIds = new LinkedHashSet<>(activeUserIds);
        personalUserIds.removeAll(sharedUserIds);

        List<SegmentMetric> segments = List.of(
                toSegment(UserSegment.OVERALL, activeUserIds, outcomesByUser, currentStreakByUser,
                        restartByUser, completionPeriod, restartPeriod, today),
                toSegment(UserSegment.PERSONAL, personalUserIds, outcomesByUser, currentStreakByUser,
                        restartByUser, completionPeriod, restartPeriod, today),
                toSegment(UserSegment.SHARED, sharedUserIds, outcomesByUser, currentStreakByUser,
                        restartByUser, completionPeriod, restartPeriod, today));

        return new AdminRetentionMetricsResponse(today, now, cohortPeriod, retention,
                retentionCohorts, northStar, segments);
    }

    private static List<RetentionCohortMetric> toRetentionCohorts(
            List<ActiveUserSnapshot> cohortUsers,
            Map<Long, Set<LocalDate>> activityDatesByUser,
            LocalDate today) {
        Map<LocalDate, List<ActiveUserSnapshot>> usersByDate = cohortUsers.stream()
                .collect(Collectors.groupingBy(ActiveUserSnapshot::signupDate));
        return usersByDate.entrySet().stream()
                .sorted(Map.Entry.<LocalDate, List<ActiveUserSnapshot>>comparingByKey().reversed())
                .map(entry -> new RetentionCohortMetric(
                        entry.getKey(),
                        entry.getValue().size(),
                        cohortPoint(1, entry.getKey(), entry.getValue(), activityDatesByUser, today),
                        cohortPoint(7, entry.getKey(), entry.getValue(), activityDatesByUser, today),
                        cohortPoint(30, entry.getKey(), entry.getValue(), activityDatesByUser, today)))
                .toList();
    }

    private static RetentionPoint cohortPoint(
            int dayOffset,
            LocalDate cohortDate,
            List<ActiveUserSnapshot> users,
            Map<Long, Set<LocalDate>> activityDatesByUser,
            LocalDate today) {
        LocalDate targetDate = cohortDate.plusDays(dayOffset);
        if (targetDate.isAfter(today)) {
            return null;
        }
        long returned = users.stream()
                .filter(user -> activityDatesByUser.getOrDefault(user.userId(), Set.of()).contains(targetDate))
                .count();
        return RetentionPoint.of(dayOffset, returned, users.size());
    }

    private static RetentionMetric aggregateRetention(
            int dayOffset,
            List<ActiveUserSnapshot> cohortUsers,
            Map<Long, Set<LocalDate>> activityDatesByUser,
            LocalDate today) {
        LocalDate eligibleSignupThrough = today.minusDays(dayOffset);
        long eligible = 0;
        long returned = 0;
        for (ActiveUserSnapshot user : cohortUsers) {
            if (user.signupDate().isAfter(eligibleSignupThrough)) {
                continue;
            }
            eligible++;
            if (activityDatesByUser.getOrDefault(user.userId(), Set.of())
                    .contains(user.signupDate().plusDays(dayOffset))) {
                returned++;
            }
        }
        return RetentionMetric.of(dayOffset, eligibleSignupThrough, returned, eligible);
    }

    private static Map<Long, Set<LocalDate>> groupActivityDates(
            List<DailyActivityMetricRow> rows,
            Set<Long> activeUserIds) {
        Map<Long, Set<LocalDate>> result = new HashMap<>();
        for (DailyActivityMetricRow row : rows) {
            if (activeUserIds.contains(row.getUserId())) {
                result.computeIfAbsent(row.getUserId(), ignored -> new HashSet<>()).add(row.getActivityDate());
            }
        }
        return result;
    }

    private static Map<Long, Set<LocalDate>> groupCompletionDates(
            List<CompletionEventMetricRow> rows,
            Set<Long> activeUserIds,
            LocalDate fromDate,
            LocalDate toDate) {
        Map<Long, Set<LocalDate>> result = new HashMap<>();
        for (CompletionEventMetricRow row : rows) {
            if (!activeUserIds.contains(row.getUserId()) || row.getCompletedAt() == null) {
                continue;
            }
            LocalDate completedDate = LocalDate.ofInstant(row.getCompletedAt(), KST);
            if (completedDate.isBefore(fromDate) || completedDate.isAfter(toDate)) {
                continue;
            }
            result.computeIfAbsent(row.getUserId(), ignored -> new HashSet<>()).add(completedDate);
        }
        return result;
    }

    private static Map<Long, Integer> currentStreaks(
            List<StreakMetricRow> rows,
            Set<Long> activeUserIds,
            LocalDate today) {
        Map<Long, Integer> result = new HashMap<>();
        for (StreakMetricRow row : rows) {
            if (!activeUserIds.contains(row.getUserId())) {
                continue;
            }
            int effective = row.getLastSuccessDate() == null
                    || row.getLastSuccessDate().isBefore(today.minusDays(1))
                    ? 0 : Math.max(0, row.getCurrentCount());
            result.merge(row.getUserId(), effective, Math::max);
        }
        return result;
    }

    private static Map<Long, RestartState> restartStates(
            Map<Long, Set<LocalDate>> completionDatesByUser,
            Set<Long> activeUserIds,
            LocalDate today) {
        Map<Long, RestartState> result = new HashMap<>();
        for (Long userId : activeUserIds) {
            List<LocalDate> dates = new ArrayList<>(completionDatesByUser.getOrDefault(userId, Set.of()));
            dates.sort(Comparator.naturalOrder());
            boolean gapExperienced = false;
            boolean restarted = false;
            for (int i = 0; i < dates.size(); i++) {
                LocalDate completionDate = dates.get(i);
                if (i + 1 < dates.size()) {
                    long gap = ChronoUnit.DAYS.between(completionDate, dates.get(i + 1)) - 1;
                    if (gap >= RESTART_EMPTY_DAYS) {
                        gapExperienced = true;
                        restarted = true;
                        break;
                    }
                } else {
                    // 오늘은 아직 끝나지 않았으므로 미복귀 사용자의 빈 날에는 포함하지 않음.
                    if (!completionDate.plusDays(RESTART_EMPTY_DAYS).isAfter(today.minusDays(1))) {
                        gapExperienced = true;
                    }
                }
            }
            result.put(userId, new RestartState(gapExperienced, restarted));
        }
        return result;
    }

    private static SegmentMetric toSegment(
            UserSegment segment,
            Set<Long> userIds,
            Map<Long, Outcome> outcomesByUser,
            Map<Long, Integer> currentStreakByUser,
            Map<Long, RestartState> restartByUser,
            DatePeriod completionPeriod,
            DatePeriod restartPeriod,
            LocalDate today) {
        long completed = 0;
        long decided = 0;
        long streakSum = 0;
        long restarted = 0;
        long gapExperienced = 0;
        for (Long userId : userIds) {
            Outcome outcome = outcomesByUser.getOrDefault(userId, Outcome.ZERO);
            completed += outcome.completed();
            decided += outcome.total();
            streakSum += currentStreakByUser.getOrDefault(userId, 0);
            RestartState restart = restartByUser.getOrDefault(userId, RestartState.NONE);
            if (restart.gapExperienced()) {
                gapExperienced++;
            }
            if (restart.restarted()) {
                restarted++;
            }
        }
        return new SegmentMetric(
                segment,
                userIds.size(),
                CompletionRateMetric.of(completionPeriod, completed, decided),
                CurrentStreakMetric.of(today, streakSum, userIds.size()),
                RestartRateMetric.of(restartPeriod, RESTART_EMPTY_DAYS, restarted, gapExperienced));
    }

    private record ActiveUserSnapshot(Long userId, LocalDate signupDate) {
    }

    private record Outcome(long completed, long total) {
        private static final Outcome ZERO = new Outcome(0, 0);

        private Outcome plus(Outcome other) {
            return new Outcome(completed + other.completed, total + other.total);
        }
    }

    private record RestartState(boolean gapExperienced, boolean restarted) {
        private static final RestartState NONE = new RestartState(false, false);
    }
}
