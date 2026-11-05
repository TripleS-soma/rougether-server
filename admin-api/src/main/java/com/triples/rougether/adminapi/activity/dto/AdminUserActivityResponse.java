package com.triples.rougether.adminapi.activity.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record AdminUserActivityResponse(
        LocalDate date, String timezone, Instant generatedAt, LocalDate minDate, LocalDate maxDate,
        Summary summary, List<FeatureDefinition> availableFeatures, List<UserActivity> items,
        int page, int size, long totalElements) {

    public record Summary(long usersWithRecords, long usersWithActions, long actionRecords, long featuresUsed) {}
    public record FeatureDefinition(UserActivityFeature code, String label, String description) {}
    public record FeatureActivity(UserActivityFeature code, String label, String description,
                                  long recordCount, Instant firstRecordedAt, Instant lastRecordedAt) {}
    public record UserActivity(Long userId, String nickname, boolean authenticatedActivity,
                               Instant firstRecordedAt, Instant lastRecordedAt, long actionRecords,
                               List<FeatureActivity> features) {}
}
