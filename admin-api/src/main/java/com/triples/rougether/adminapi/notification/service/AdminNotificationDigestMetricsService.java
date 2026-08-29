package com.triples.rougether.adminapi.notification.service;

import com.triples.rougether.adminapi.notification.dto.AdminNotificationDigestMetricsResponse;
import com.triples.rougether.adminapi.notification.dto.AdminNotificationDigestMetricsResponse.DayMetric;
import com.triples.rougether.domain.notification.digest.entity.DailyIncompleteDigest;
import com.triples.rougether.domain.notification.digest.entity.DailyIncompleteDigestTargetType;
import com.triples.rougether.domain.notification.digest.repository.DailyDigestTargetCompletionEventRow;
import com.triples.rougether.domain.notification.digest.repository.DailyIncompleteDigestRepository;
import com.triples.rougether.domain.notification.digest.repository.DailyIncompleteDigestTargetRepository;
import com.triples.rougether.domain.notification.entity.PushStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminNotificationDigestMetricsService {

    public static final int DEFAULT_DAYS = 7;
    public static final int MAX_DAYS = 90;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Duration CONVERSION_WINDOW = Duration.ofMinutes(120);

    private final DailyIncompleteDigestRepository digestRepository;
    private final DailyIncompleteDigestTargetRepository targetRepository;
    private final Clock clock;

    public AdminNotificationDigestMetricsService(DailyIncompleteDigestRepository digestRepository,
                                                 DailyIncompleteDigestTargetRepository targetRepository,
                                                 Clock clock) {
        this.digestRepository = digestRepository;
        this.targetRepository = targetRepository;
        this.clock = clock;
    }

    public AdminNotificationDigestMetricsResponse getMetrics(int days) {
        int boundedDays = Math.min(Math.max(days, 1), MAX_DAYS);
        Instant now = clock.instant();
        LocalDate todayKst = LocalDate.ofInstant(now, KST);
        LocalDate fromDate = todayKst.minusDays(boundedDays - 1L);
        List<DailyIncompleteDigest> digests = digestRepository.findWithNotificationBetween(fromDate, todayKst);
        Map<Long, DailyIncompleteDigest> sentDigests = sentDigestMap(digests);
        Set<Long> convertedDigestIds = findConvertedDigestIds(fromDate, todayKst, sentDigests);

        Map<LocalDate, List<DailyIncompleteDigest>> digestsByDate = new HashMap<>();
        for (DailyIncompleteDigest digest : digests) {
            digestsByDate.computeIfAbsent(digest.getDigestDate(), ignored -> new ArrayList<>()).add(digest);
        }

        List<DayMetric> metrics = new ArrayList<>(boundedDays);
        for (int i = 0; i < boundedDays; i++) {
            LocalDate date = todayKst.minusDays(i);
            metrics.add(toDayMetric(date, digestsByDate.getOrDefault(date, List.of()), convertedDigestIds, now));
        }
        return new AdminNotificationDigestMetricsResponse(metrics, now);
    }

    private static Map<Long, DailyIncompleteDigest> sentDigestMap(List<DailyIncompleteDigest> digests) {
        Map<Long, DailyIncompleteDigest> result = new HashMap<>();
        for (DailyIncompleteDigest digest : digests) {
            if (digest.getPushStatus() == PushStatus.SENT && digest.getSentAt() != null) {
                result.put(digest.getId(), digest);
            }
        }
        return result;
    }

    private Set<Long> findConvertedDigestIds(LocalDate fromDate,
                                             LocalDate toDate,
                                             Map<Long, DailyIncompleteDigest> sentDigests) {
        if (sentDigests.isEmpty()) {
            return Set.of();
        }
        Instant completedBefore = sentDigests.values().stream()
                .map(DailyIncompleteDigest::getSentAt)
                .max(Instant::compareTo)
                .orElseThrow()
                .plus(CONVERSION_WINDOW);
        Set<Long> converted = new HashSet<>();
        addConverted(converted, targetRepository.findCompletedRoutineTargetsAfterDigestSent(
                DailyIncompleteDigestTargetType.ROUTINE, fromDate, toDate, completedBefore));
        addConverted(converted, targetRepository.findCompletedTodoTargetsAfterDigestSent(
                DailyIncompleteDigestTargetType.TODO, fromDate, toDate, completedBefore));
        return converted;
    }

    private static void addConverted(Set<Long> converted,
                                     List<DailyDigestTargetCompletionEventRow> rows) {
        for (DailyDigestTargetCompletionEventRow row : rows) {
            if (row.getDigestSentAt() == null || row.getCompletedAt() == null) {
                continue;
            }
            Instant sentAt = row.getDigestSentAt();
            if (!row.getCompletedAt().isBefore(sentAt)
                    && row.getCompletedAt().isBefore(sentAt.plus(CONVERSION_WINDOW))) {
                converted.add(row.getDigestId());
            }
        }
    }

    private static DayMetric toDayMetric(LocalDate date,
                                         List<DailyIncompleteDigest> digests,
                                         Set<Long> convertedDigestIds,
                                         Instant now) {
        Map<PushStatus, Long> counts = new EnumMap<>(PushStatus.class);
        long conversionMeasured = 0;
        long converted = 0;
        long conversionPending = 0;
        for (DailyIncompleteDigest digest : digests) {
            counts.merge(digest.getPushStatus(), 1L, Long::sum);
            if (digest.getPushStatus() != PushStatus.SENT || digest.getSentAt() == null) {
                continue;
            }
            if (digest.getSentAt().plus(CONVERSION_WINDOW).isAfter(now)) {
                conversionPending++;
                continue;
            }
            conversionMeasured++;
            if (convertedDigestIds.contains(digest.getId())) {
                converted++;
            }
        }
        return DayMetric.of(
                date,
                digests.size(),
                counts.getOrDefault(PushStatus.PENDING, 0L),
                counts.getOrDefault(PushStatus.SENT, 0L),
                counts.getOrDefault(PushStatus.BLOCKED, 0L),
                counts.getOrDefault(PushStatus.FAILED, 0L),
                conversionMeasured,
                converted,
                conversionPending);
    }
}
