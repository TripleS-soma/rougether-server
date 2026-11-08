package com.triples.rougether.adminapi.notification.campaign.dto;

public record NotificationCampaignPreviewResponse(
        int recipientCount,
        int pushAllowedCount,
        int blockedCount,
        int tokenlessCount,
        int pushNotRequestedCount) {
}
