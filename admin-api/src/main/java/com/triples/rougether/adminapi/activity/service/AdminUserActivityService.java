package com.triples.rougether.adminapi.activity.service;

import com.triples.rougether.adminapi.activity.dto.AdminUserActivityResponse;
import com.triples.rougether.adminapi.activity.dto.AdminUserActivityResponse.FeatureActivity;
import com.triples.rougether.adminapi.activity.dto.AdminUserActivityResponse.FeatureDefinition;
import com.triples.rougether.adminapi.activity.dto.AdminUserActivityResponse.Summary;
import com.triples.rougether.adminapi.activity.dto.AdminUserActivityResponse.UserActivity;
import com.triples.rougether.adminapi.activity.dto.UserActivityFeature;
import com.triples.rougether.adminapi.activity.error.AdminActivityDateInvalidException;
import com.triples.rougether.domain.activity.repository.AdminUserActivityRepository.FeatureRecord;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class AdminUserActivityService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public static final int LOOKBACK_DAYS = 365;
    private final AdminUserActivityReader reader;
    private final Clock clock;

    public AdminUserActivityService(AdminUserActivityReader reader, Clock clock) {
        this.reader = reader;
        this.clock = clock;
    }

    public LocalDate today() { return LocalDate.ofInstant(clock.instant(), KST); }

    public AdminUserActivityResponse getActivities(LocalDate requestedDate, String query,
            UserActivityFeature feature, boolean actionsOnly, int requestedPage, int requestedSize) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, KST);
        LocalDate date = requestedDate == null ? today : requestedDate;
        if (date.isAfter(today) || date.isBefore(today.minusDays(LOOKBACK_DAYS))) {
            throw new AdminActivityDateInvalidException();
        }
        AdminUserActivityReader.Snapshot snapshot = reader.read(date);
        List<FeatureRecord> records = snapshot.records();
        Map<Long, List<FeatureRecord>> byUser = new LinkedHashMap<>();
        for (FeatureRecord record : records) {
            byUser.computeIfAbsent(record.getUserId(), ignored -> new ArrayList<>()).add(record);
        }
        String term = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<UserActivity> users = byUser.values().stream().map(this::toUser)
                .filter(user -> term.isEmpty() || String.valueOf(user.userId()).contains(term)
                        || (user.nickname() != null && user.nickname().toLowerCase(Locale.ROOT).contains(term)))
                .filter(user -> !actionsOnly || user.actionRecords() > 0)
                .filter(user -> feature == null || (feature == UserActivityFeature.ACCESS
                        ? user.authenticatedActivity()
                        : user.features().stream().anyMatch(activity -> activity.code() == feature)))
                .sorted(Comparator.comparing(UserActivity::lastRecordedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(UserActivity::userId, Comparator.reverseOrder()))
                .toList();
        long actionUsers = users.stream().filter(user -> user.actionRecords() > 0).count();
        long actions = users.stream().mapToLong(UserActivity::actionRecords).sum();
        long featureCount = users.stream().flatMap(user -> user.features().stream())
                .map(FeatureActivity::code).distinct().count();
        int size = Math.clamp(requestedSize, 1, 100);
        int lastPage = users.isEmpty() ? 0 : (users.size() - 1) / size;
        int page = Math.clamp(requestedPage, 0, lastPage);
        List<UserActivity> items = users.stream().skip((long) page * size).limit(size).toList();
        List<FeatureDefinition> definitions = Arrays.stream(UserActivityFeature.values())
                .map(value -> new FeatureDefinition(value, value.label(), value.description())).toList();
        return new AdminUserActivityResponse(date, KST.getId(), snapshot.generatedAt(), today.minusDays(LOOKBACK_DAYS), today,
                new Summary(users.size(), actionUsers, actions, featureCount), definitions,
                items, page, size, users.size());
    }

    // JDBC의 serverTimezone 해석을 거치지 않는 UTC epoch로 전송한다.
    static Instant toInstant(BigDecimal epoch) {
        if (epoch == null) return null;
        return Instant.ofEpochSecond(epoch.longValue(), epoch.remainder(BigDecimal.ONE).movePointRight(9).longValue());
    }

    private UserActivity toUser(List<FeatureRecord> records) {
        FeatureRecord first = records.getFirst();
        List<FeatureActivity> features = records.stream()
                .filter(record -> !record.getFeature().equals(UserActivityFeature.ACCESS.name()))
                .map(record -> {
                    UserActivityFeature feature = UserActivityFeature.valueOf(record.getFeature());
                    return new FeatureActivity(feature, feature.label(), feature.description(), record.getRecordCount(),
                            toInstant(record.getFirstRecordedEpoch()), toInstant(record.getLastRecordedEpoch()));
                })
                .sorted(Comparator.comparing(FeatureActivity::lastRecordedAt).reversed()
                        .thenComparing(FeatureActivity::code))
                .toList();
        boolean authenticated = records.stream().anyMatch(record -> record.getFeature().equals("ACCESS"));
        // 일별 인증 기록의 created_at은 배치 적재 시각이다. 실제 요청 시각으로 표시하지 않는다.
        Instant firstAt = records.stream().map(record -> toInstant(record.getFirstRecordedEpoch())).filter(Objects::nonNull)
                .min(Instant::compareTo).orElse(null);
        Instant lastAt = records.stream().map(record -> toInstant(record.getLastRecordedEpoch())).filter(Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
        return new UserActivity(first.getUserId(), first.getNickname(), authenticated, firstAt, lastAt,
                features.stream().mapToLong(FeatureActivity::recordCount).sum(), features);
    }
}
