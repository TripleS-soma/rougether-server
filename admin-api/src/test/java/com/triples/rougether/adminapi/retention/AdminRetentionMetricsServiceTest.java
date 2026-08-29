package com.triples.rougether.adminapi.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.adminapi.retention.dto.AdminRetentionMetricsResponse;
import com.triples.rougether.adminapi.retention.dto.AdminRetentionMetricsResponse.RetentionCohortMetric;
import com.triples.rougether.adminapi.retention.dto.AdminRetentionMetricsResponse.SegmentMetric;
import com.triples.rougether.adminapi.retention.dto.AdminRetentionMetricsResponse.UserSegment;
import com.triples.rougether.adminapi.retention.service.AdminRetentionMetricsService;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminRetentionMetricsServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2030, 8, 20);
    private static final Instant NOW = LocalDateTime.of(2030, 8, 20, 12, 0).atZone(KST).toInstant();

    @Mock
    AdminRetentionMetricRepository metricRepository;

    AdminRetentionMetricsService service;

    @BeforeEach
    void setUp() {
        service = new AdminRetentionMetricsService(metricRepository, Clock.fixed(NOW, KST));
    }

    @Test
    void 정확한_D1_D7_D30과_북극성_세그먼트별_KPI를_계산한다() {
        LocalDate user1Signup = TODAY.minusDays(31);
        LocalDate user2Signup = TODAY.minusDays(8);
        LocalDate user3Signup = TODAY.minusDays(1);
        when(metricRepository.findActiveUsers()).thenReturn(List.of(
                activeUser(1L, user1Signup),
                activeUser(2L, user2Signup),
                activeUser(3L, user3Signup),
                activeUser(4L, TODAY)));
        when(metricRepository.findActivitiesBetween(
                TODAY.minusDays(34).plusDays(1), TODAY,
                TODAY.minusDays(34).atStartOfDay(KST).toInstant()))
                .thenReturn(List.of(
                        activity(1L, user1Signup.plusDays(1)),
                        activity(1L, user1Signup.plusDays(7)),
                        activity(1L, user1Signup.plusDays(30)),
                        activity(2L, user2Signup.plusDays(1)),
                        activity(3L, user3Signup.plusDays(1))));
        when(metricRepository.findCompletionEvents(
                TODAY.minusDays(29).atStartOfDay(KST).toInstant(), NOW))
                .thenReturn(List.of(
                        completion(1L, TODAY.minusDays(6)),
                        completion(1L, TODAY.minusDays(3)),
                        completion(1L, TODAY),
                        completion(2L, TODAY.minusDays(29)),
                        completion(2L, TODAY.minusDays(25)),
                        completion(2L, TODAY.minusDays(1)),
                        completion(3L, TODAY.minusDays(10))));
        when(metricRepository.findRoutineOutcomesBetween(TODAY.minusDays(30), TODAY.minusDays(1)))
                .thenReturn(List.of(
                        outcome(1L, 3, 4),
                        outcome(2L, 1, 2),
                        outcome(3L, 0, 1)));
        when(metricRepository.findStreaksForActiveUsers()).thenReturn(List.of(
                streak(1L, 5, TODAY),
                streak(2L, 4, TODAY.minusDays(1)),
                streak(3L, 7, TODAY.minusDays(2))));
        when(metricRepository.findSharedUserIds(HouseMemberStatus.ACTIVE)).thenReturn(List.of(1L, 2L));

        AdminRetentionMetricsResponse response = service.getMetrics(35);

        assertThat(response.asOfDate()).isEqualTo(TODAY);
        assertThat(response.generatedAt()).isEqualTo(NOW);
        assertThat(response.cohortPeriod().fromDate()).isEqualTo(TODAY.minusDays(34));
        assertThat(response.cohortPeriod().toDate()).isEqualTo(TODAY);
        assertThat(response.cohortPeriod().days()).isEqualTo(35);
        assertThat(response.retention().activityDefinition()).isEqualTo("AUTHENTICATED_REQUEST_EXACT_DAY");

        assertThat(response.retention().d1().returnedUserCount()).isEqualTo(3);
        assertThat(response.retention().d1().eligibleUserCount()).isEqualTo(3);
        assertThat(response.retention().d1().percentage()).isEqualTo(100.0);
        assertThat(response.retention().d7().returnedUserCount()).isEqualTo(1);
        assertThat(response.retention().d7().eligibleUserCount()).isEqualTo(2);
        assertThat(response.retention().d7().percentage()).isEqualTo(50.0);
        assertThat(response.retention().d30().returnedUserCount()).isEqualTo(1);
        assertThat(response.retention().d30().eligibleUserCount()).isEqualTo(1);
        assertThat(response.retention().d30().percentage()).isEqualTo(100.0);
        assertThat(response.retentionCohorts()).hasSize(4);
        RetentionCohortMetric newest = cohort(response, TODAY);
        assertThat(newest.cohortUserCount()).isEqualTo(1);
        assertThat(newest.d1()).isNull();
        assertThat(newest.d7()).isNull();
        assertThat(newest.d30()).isNull();
        RetentionCohortMetric oneDayOld = cohort(response, user3Signup);
        assertThat(oneDayOld.d1().returnedUserCount()).isEqualTo(1);
        assertThat(oneDayOld.d1().eligibleUserCount()).isEqualTo(1);
        assertThat(oneDayOld.d7()).isNull();
        RetentionCohortMetric eightDaysOld = cohort(response, user2Signup);
        assertThat(eightDaysOld.d1().percentage()).isEqualTo(100.0);
        assertThat(eightDaysOld.d7().percentage()).isEqualTo(0.0);
        assertThat(eightDaysOld.d30()).isNull();
        RetentionCohortMetric mature = cohort(response, user1Signup);
        assertThat(mature.d1().percentage()).isEqualTo(100.0);
        assertThat(mature.d7().percentage()).isEqualTo(100.0);
        assertThat(mature.d30().percentage()).isEqualTo(100.0);

        assertThat(response.northStar().period().fromDate()).isEqualTo(TODAY.minusDays(6));
        assertThat(response.northStar().period().toDate()).isEqualTo(TODAY);
        assertThat(response.northStar().minimumDistinctCompletionDays()).isEqualTo(3);
        assertThat(response.northStar().qualifiedUserCount()).isEqualTo(1);
        assertThat(response.northStar().activeUserCount()).isEqualTo(4);
        assertThat(response.northStar().percentage()).isEqualTo(25.0);

        SegmentMetric overall = segment(response, UserSegment.OVERALL);
        assertThat(overall.userCount()).isEqualTo(4);
        assertThat(overall.completionRate().completedLogCount()).isEqualTo(4);
        assertThat(overall.completionRate().decidedLogCount()).isEqualTo(7);
        assertThat(overall.completionRate().percentage()).isEqualTo(57.1);
        assertThat(overall.currentStreak().currentStreakSum()).isEqualTo(9);
        assertThat(overall.currentStreak().userCount()).isEqualTo(4);
        assertThat(overall.currentStreak().average()).isEqualTo(2.3);
        assertThat(overall.restartRate().restartedUserCount()).isEqualTo(1);
        assertThat(overall.restartRate().gapExperiencedUserCount()).isEqualTo(2);
        assertThat(overall.restartRate().percentage()).isEqualTo(50.0);

        SegmentMetric personal = segment(response, UserSegment.PERSONAL);
        assertThat(personal.userCount()).isEqualTo(2);
        assertThat(personal.completionRate().percentage()).isEqualTo(0.0);
        assertThat(personal.currentStreak().average()).isEqualTo(0.0);
        assertThat(personal.restartRate().restartedUserCount()).isZero();
        assertThat(personal.restartRate().gapExperiencedUserCount()).isEqualTo(1);

        SegmentMetric shared = segment(response, UserSegment.SHARED);
        assertThat(shared.userCount()).isEqualTo(2);
        assertThat(shared.completionRate().percentage()).isEqualTo(66.7);
        assertThat(shared.currentStreak().average()).isEqualTo(4.5);
        assertThat(shared.restartRate().percentage()).isEqualTo(100.0);
    }

    @Test
    void 가입시각의_KST_자정_경계로_코호트를_분리한다() {
        // UTC 14:59:59 = KST 23:59:59, UTC 15:00:00 = 다음 KST 날짜 00:00:00.
        Instant beforeKstMidnight = Instant.parse("2030-08-01T14:59:59Z");
        Instant atKstMidnight = Instant.parse("2030-08-01T15:00:00Z");
        when(metricRepository.findActiveUsers()).thenReturn(List.of(
                new ActiveUserRow(10L, beforeKstMidnight),
                new ActiveUserRow(11L, atKstMidnight)));
        when(metricRepository.findActivitiesBetween(
                TODAY.minusDays(34).plusDays(1), TODAY,
                TODAY.minusDays(34).atStartOfDay(KST).toInstant()))
                .thenReturn(List.of(
                        activity(10L, LocalDate.of(2030, 8, 2)),
                        activity(11L, LocalDate.of(2030, 8, 3))));
        when(metricRepository.findCompletionEvents(
                TODAY.minusDays(29).atStartOfDay(KST).toInstant(), NOW)).thenReturn(List.of());
        when(metricRepository.findRoutineOutcomesBetween(TODAY.minusDays(30), TODAY.minusDays(1)))
                .thenReturn(List.of());
        when(metricRepository.findStreaksForActiveUsers()).thenReturn(List.of());
        when(metricRepository.findSharedUserIds(HouseMemberStatus.ACTIVE)).thenReturn(List.of());

        AdminRetentionMetricsResponse response = service.getMetrics(35);

        assertThat(response.retentionCohorts()).extracting(RetentionCohortMetric::cohortDate)
                .containsExactly(LocalDate.of(2030, 8, 2), LocalDate.of(2030, 8, 1));
        assertThat(cohort(response, LocalDate.of(2030, 8, 1)).d1().percentage()).isEqualTo(100.0);
        assertThat(cohort(response, LocalDate.of(2030, 8, 2)).d1().percentage()).isEqualTo(100.0);
    }

    @Test
    void 미복귀_공백은_끝난_KST_날짜만_세어_오늘_직전_경계를_과대계상하지_않는다() {
        when(metricRepository.findActiveUsers()).thenReturn(List.of(
                activeUser(20L, TODAY), activeUser(21L, TODAY)));
        when(metricRepository.findActivitiesBetween(
                TODAY, TODAY, TODAY.atStartOfDay(KST).toInstant())).thenReturn(List.of());
        when(metricRepository.findCompletionEvents(
                TODAY.minusDays(29).atStartOfDay(KST).toInstant(), NOW))
                .thenReturn(List.of(
                        completion(20L, TODAY.minusDays(3)),
                        completion(21L, TODAY.minusDays(4))));
        when(metricRepository.findRoutineOutcomesBetween(TODAY.minusDays(30), TODAY.minusDays(1)))
                .thenReturn(List.of());
        when(metricRepository.findStreaksForActiveUsers()).thenReturn(List.of());
        when(metricRepository.findSharedUserIds(HouseMemberStatus.ACTIVE)).thenReturn(List.of());

        SegmentMetric overall = segment(service.getMetrics(1), UserSegment.OVERALL);

        assertThat(overall.restartRate().gapExperiencedUserCount()).isEqualTo(1);
        assertThat(overall.restartRate().restartedUserCount()).isZero();
        assertThat(overall.restartRate().percentage()).isZero();
    }

    @Test
    void completedAt의_UTC_145959와_150000을_서로_다른_KST_완료일로_센다() {
        when(metricRepository.findActiveUsers()).thenReturn(List.of(activeUser(30L, TODAY.minusDays(10))));
        when(metricRepository.findActivitiesBetween(
                TODAY.minusDays(34).plusDays(1), TODAY,
                TODAY.minusDays(34).atStartOfDay(KST).toInstant())).thenReturn(List.of());
        when(metricRepository.findCompletionEvents(
                TODAY.minusDays(29).atStartOfDay(KST).toInstant(), NOW))
                .thenReturn(List.of(
                        new CompletionRow(30L, Instant.parse("2030-08-16T14:59:59Z")),
                        new CompletionRow(30L, Instant.parse("2030-08-16T15:00:00Z")),
                        new CompletionRow(30L, Instant.parse("2030-08-18T03:00:00Z"))));
        when(metricRepository.findRoutineOutcomesBetween(TODAY.minusDays(30), TODAY.minusDays(1)))
                .thenReturn(List.of());
        when(metricRepository.findStreaksForActiveUsers()).thenReturn(List.of());
        when(metricRepository.findSharedUserIds(HouseMemberStatus.ACTIVE)).thenReturn(List.of());

        AdminRetentionMetricsResponse response = service.getMetrics(35);

        assertThat(response.northStar().qualifiedUserCount()).isEqualTo(1);
        assertThat(response.northStar().activeUserCount()).isEqualTo(1);
        assertThat(response.northStar().percentage()).isEqualTo(100.0);
    }

    @Test
    void 조회범위를_1일부터_90일까지_묶고_분모가_없으면_비율은_0이다() {
        when(metricRepository.findActiveUsers()).thenReturn(List.of());
        when(metricRepository.findActivitiesBetween(
                TODAY, TODAY, TODAY.atStartOfDay(KST).toInstant())).thenReturn(List.of());
        when(metricRepository.findCompletionEvents(TODAY.minusDays(29).atStartOfDay(KST).toInstant(), NOW))
                .thenReturn(List.of());
        when(metricRepository.findRoutineOutcomesBetween(TODAY.minusDays(30), TODAY.minusDays(1)))
                .thenReturn(List.of());
        when(metricRepository.findStreaksForActiveUsers()).thenReturn(List.of());
        when(metricRepository.findSharedUserIds(HouseMemberStatus.ACTIVE)).thenReturn(List.of());

        AdminRetentionMetricsResponse response = service.getMetrics(0);

        assertThat(response.cohortPeriod().days()).isEqualTo(1);
        assertThat(response.retention().d1().percentage()).isZero();
        assertThat(response.northStar().percentage()).isZero();
        assertThat(segment(response, UserSegment.OVERALL).completionRate().percentage()).isZero();
        assertThat(segment(response, UserSegment.OVERALL).currentStreak().average()).isZero();
        assertThat(segment(response, UserSegment.OVERALL).restartRate().percentage()).isZero();
        verify(metricRepository).findActivitiesBetween(
                TODAY, TODAY, TODAY.atStartOfDay(KST).toInstant());
    }

    @Test
    void 코호트_조회범위_상한은_90일이다() {
        LocalDate cohortFrom = TODAY.minusDays(89);
        when(metricRepository.findActiveUsers()).thenReturn(List.of());
        when(metricRepository.findActivitiesBetween(
                cohortFrom.plusDays(1), TODAY, cohortFrom.atStartOfDay(KST).toInstant()))
                .thenReturn(List.of());
        when(metricRepository.findCompletionEvents(TODAY.minusDays(29).atStartOfDay(KST).toInstant(), NOW))
                .thenReturn(List.of());
        when(metricRepository.findRoutineOutcomesBetween(TODAY.minusDays(30), TODAY.minusDays(1)))
                .thenReturn(List.of());
        when(metricRepository.findStreaksForActiveUsers()).thenReturn(List.of());
        when(metricRepository.findSharedUserIds(HouseMemberStatus.ACTIVE)).thenReturn(List.of());

        AdminRetentionMetricsResponse response = service.getMetrics(999);

        assertThat(response.cohortPeriod().days()).isEqualTo(90);
        assertThat(response.cohortPeriod().fromDate()).isEqualTo(cohortFrom);
        verify(metricRepository).findActivitiesBetween(
                cohortFrom.plusDays(1), TODAY, cohortFrom.atStartOfDay(KST).toInstant());
    }

    private static SegmentMetric segment(AdminRetentionMetricsResponse response, UserSegment segment) {
        return response.segments().stream()
                .filter(metric -> metric.segment() == segment)
                .findFirst()
                .orElseThrow();
    }

    private static RetentionCohortMetric cohort(AdminRetentionMetricsResponse response, LocalDate cohortDate) {
        return response.retentionCohorts().stream()
                .filter(metric -> metric.cohortDate().equals(cohortDate))
                .findFirst()
                .orElseThrow();
    }

    private static ActiveUserMetricRow activeUser(long userId, LocalDate signupDate) {
        return new ActiveUserRow(userId, signupDate.atStartOfDay(KST).toInstant());
    }

    private static DailyActivityMetricRow activity(long userId, LocalDate activityDate) {
        return new ActivityRow(userId, activityDate);
    }

    private static CompletionEventMetricRow completion(long userId, LocalDate completedDate) {
        return new CompletionRow(userId, completedDate.atTime(12, 0).atZone(KST).toInstant());
    }

    private static RoutineOutcomeMetricRow outcome(long userId, long completedCount, long totalCount) {
        return new OutcomeRow(userId, completedCount, totalCount);
    }

    private static StreakMetricRow streak(long userId, int currentCount, LocalDate lastSuccessDate) {
        return new StreakRow(userId, currentCount, lastSuccessDate);
    }

    private record ActiveUserRow(Long userId, Instant createdAt) implements ActiveUserMetricRow {
        @Override public Long getUserId() { return userId; }
        @Override public Instant getCreatedAt() { return createdAt; }
    }

    private record ActivityRow(Long userId, LocalDate activityDate) implements DailyActivityMetricRow {
        @Override public Long getUserId() { return userId; }
        @Override public LocalDate getActivityDate() { return activityDate; }
    }

    private record CompletionRow(Long userId, Instant completedAt) implements CompletionEventMetricRow {
        @Override public Long getUserId() { return userId; }
        @Override public Instant getCompletedAt() { return completedAt; }
    }

    private record OutcomeRow(Long userId, long completedCount, long totalCount)
            implements RoutineOutcomeMetricRow {
        @Override public Long getUserId() { return userId; }
        @Override public long getCompletedCount() { return completedCount; }
        @Override public long getTotalCount() { return totalCount; }
    }

    private record StreakRow(Long userId, int currentCount, LocalDate lastSuccessDate) implements StreakMetricRow {
        @Override public Long getUserId() { return userId; }
        @Override public int getCurrentCount() { return currentCount; }
        @Override public LocalDate getLastSuccessDate() { return lastSuccessDate; }
    }
}
