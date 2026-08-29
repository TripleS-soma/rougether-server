package com.triples.rougether.adminapi.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.triples.rougether.adminapi.notification.dto.AdminNotificationDigestMetricsResponse;
import com.triples.rougether.adminapi.notification.dto.AdminNotificationDigestMetricsResponse.DayMetric;
import com.triples.rougether.adminapi.notification.service.AdminNotificationDigestMetricsService;
import com.triples.rougether.domain.notification.digest.entity.DailyIncompleteDigest;
import com.triples.rougether.domain.notification.digest.entity.DailyIncompleteDigestTargetType;
import com.triples.rougether.domain.notification.digest.repository.DailyDigestTargetCompletionEventRow;
import com.triples.rougether.domain.notification.digest.repository.DailyIncompleteDigestRepository;
import com.triples.rougether.domain.notification.digest.repository.DailyIncompleteDigestTargetRepository;
import com.triples.rougether.domain.notification.entity.PushStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminNotificationDigestMetricsServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = LocalDateTime.of(2030, 9, 2, 12, 0).atZone(KST).toInstant();
    private static final LocalDate TODAY = LocalDate.of(2030, 9, 2);

    private final DailyIncompleteDigestRepository digestRepository = mock(DailyIncompleteDigestRepository.class);
    private final DailyIncompleteDigestTargetRepository targetRepository =
            mock(DailyIncompleteDigestTargetRepository.class);
    private final AdminNotificationDigestMetricsService service = new AdminNotificationDigestMetricsService(
            digestRepository, targetRepository, Clock.fixed(NOW, KST));

    @Test
    void 최신_일자부터_빈_날을_포함해_상태와_전환율을_집계한다() {
        Instant yesterdaySent = LocalDateTime.of(2030, 9, 1, 21, 0).atZone(KST).toInstant();
        Instant todaySent = NOW.minusSeconds(30 * 60);
        DailyIncompleteDigest converted = digest(1L, TODAY.minusDays(1), PushStatus.SENT, yesterdaySent, yesterdaySent);
        DailyIncompleteDigest notConverted = digest(2L, TODAY.minusDays(1), PushStatus.SENT, yesterdaySent, yesterdaySent);
        DailyIncompleteDigest failed = digest(3L, TODAY.minusDays(1), PushStatus.FAILED, null, yesterdaySent);
        DailyIncompleteDigest pendingConversion = digest(
                4L, TODAY, PushStatus.SENT, todaySent, NOW.minusSeconds(3 * 60 * 60));
        DailyIncompleteDigest blocked = digest(5L, TODAY, PushStatus.BLOCKED, null, todaySent);
        when(digestRepository.findWithNotificationBetween(TODAY.minusDays(2), TODAY))
                .thenReturn(List.of(converted, notConverted, failed, pendingConversion, blocked));
        when(targetRepository.findCompletedRoutineTargetsAfterDigestSent(
                eq(DailyIncompleteDigestTargetType.ROUTINE), eq(TODAY.minusDays(2)), eq(TODAY),
                any()))
                .thenReturn(List.of(completion(1L, yesterdaySent, yesterdaySent.plusSeconds(30 * 60)),
                        completion(2L, yesterdaySent, yesterdaySent.plusSeconds(121 * 60))));
        when(targetRepository.findCompletedTodoTargetsAfterDigestSent(
                eq(DailyIncompleteDigestTargetType.TODO), eq(TODAY.minusDays(2)), eq(TODAY),
                any())).thenReturn(List.of());

        AdminNotificationDigestMetricsResponse response = service.getMetrics(3);

        assertThat(response.generatedAt()).isEqualTo(NOW);
        DayMetric today = response.days().get(0);
        assertThat(today.date()).isEqualTo(TODAY);
        assertThat(today.createdCount()).isEqualTo(2);
        assertThat(today.sentCount()).isEqualTo(1);
        assertThat(today.blockedCount()).isEqualTo(1);
        assertThat(today.sentRate()).isEqualTo(50.0);
        assertThat(today.conversionMeasuredCount()).isZero();
        assertThat(today.conversionPendingCount()).isEqualTo(1);

        DayMetric yesterday = response.days().get(1);
        assertThat(yesterday.date()).isEqualTo(TODAY.minusDays(1));
        assertThat(yesterday.createdCount()).isEqualTo(3);
        assertThat(yesterday.sentCount()).isEqualTo(2);
        assertThat(yesterday.failedCount()).isEqualTo(1);
        assertThat(yesterday.conversionMeasuredCount()).isEqualTo(2);
        assertThat(yesterday.convertedCount()).isEqualTo(1);
        assertThat(yesterday.conversionRate()).isEqualTo(50.0);

        DayMetric empty = response.days().get(2);
        assertThat(empty.date()).isEqualTo(TODAY.minusDays(2));
        assertThat(empty.createdCount()).isZero();
        assertThat(empty.sentRate()).isZero();
    }

    @Test
    void 조회_일수는_서비스에서도_방어적으로_묶는다() {
        when(digestRepository.findWithNotificationBetween(any(), any())).thenReturn(List.of());

        assertThat(service.getMetrics(0).days()).hasSize(1);
        assertThat(service.getMetrics(999).days()).hasSize(AdminNotificationDigestMetricsService.MAX_DAYS);
    }

    @Test
    void 발송_결과가_정해지지_않은_digest는_PENDING으로_노출하고_전환_분모에서_제외한다() {
        DailyIncompleteDigest pending = digest(10L, TODAY, PushStatus.PENDING, null, NOW.minusSeconds(60));
        when(digestRepository.findWithNotificationBetween(TODAY, TODAY)).thenReturn(List.of(pending));

        DayMetric metric = service.getMetrics(1).days().getFirst();

        assertThat(metric.createdCount()).isEqualTo(1);
        assertThat(metric.pendingCount()).isEqualTo(1);
        assertThat(metric.sentCount()).isZero();
        assertThat(metric.conversionMeasuredCount()).isZero();
        assertThat(metric.conversionPendingCount()).isZero();
    }

    private static DailyIncompleteDigest digest(
            Long id, LocalDate date, PushStatus status, Instant sentAt, Instant createdAt) {
        DailyIncompleteDigest digest = mock(DailyIncompleteDigest.class);
        when(digest.getId()).thenReturn(id);
        when(digest.getDigestDate()).thenReturn(date);
        when(digest.getPushStatus()).thenReturn(status);
        when(digest.getSentAt()).thenReturn(sentAt);
        when(digest.getCreatedAt()).thenReturn(createdAt);
        return digest;
    }

    private record Completion(Long digestId, Instant digestSentAt, Instant completedAt)
            implements DailyDigestTargetCompletionEventRow {
        @Override
        public Long getDigestId() {
            return digestId;
        }

        @Override
        public Instant getDigestSentAt() {
            return digestSentAt;
        }

        @Override
        public Instant getCompletedAt() {
            return completedAt;
        }
    }

    private static DailyDigestTargetCompletionEventRow completion(Long digestId, Instant digestSentAt,
                                                                  Instant completedAt) {
        return new Completion(digestId, digestSentAt, completedAt);
    }
}
