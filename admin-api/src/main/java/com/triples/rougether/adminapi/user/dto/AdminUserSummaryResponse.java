package com.triples.rougether.adminapi.user.dto;

import java.time.Instant;

public record AdminUserSummaryResponse(
        long activeUsers,
        long newUsersLast7Days,
        long recentlyAccessedLast7Days,
        long onboardingCompletedUsers,
        double onboardingCompletionRate,
        Instant generatedAt) {

    public static AdminUserSummaryResponse of(long activeUsers,
                                              long newUsersLast7Days,
                                              long recentlyAccessedLast7Days,
                                              long onboardingCompletedUsers,
                                              Instant generatedAt) {
        double completionRate = activeUsers == 0
                ? 0.0
                : onboardingCompletedUsers * 100.0 / activeUsers;
        return new AdminUserSummaryResponse(
                activeUsers,
                newUsersLast7Days,
                recentlyAccessedLast7Days,
                onboardingCompletedUsers,
                completionRate,
                generatedAt);
    }
}
