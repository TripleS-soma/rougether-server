package com.triples.rougether.adminapi.notification.campaign.dto;

import com.triples.rougether.domain.notification.campaign.entity.NotificationCampaign.Audience;
import com.triples.rougether.domain.notification.campaign.entity.NotificationCampaign.Status;
import java.time.Instant;

public record NotificationCampaignResponse(
        Long id, String title, String body, Audience audience, boolean pushEnabled, Status status,
        int recipientCount, long createdCount, long pushSentCount, long pushFailedCount,
        long pushBlockedCount, long pushPendingCount, long pushNotRequestedCount,
        long skippedCount, long processingFailedCount, Instant createdAt, String createdByUsername,
        String approvedByUsername, Instant approvedAt) {
}
