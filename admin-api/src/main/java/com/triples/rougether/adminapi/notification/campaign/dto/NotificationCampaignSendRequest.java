package com.triples.rougether.adminapi.notification.campaign.dto;

import jakarta.validation.constraints.*;

public record NotificationCampaignSendRequest(
        @NotNull @AssertTrue Boolean confirmed,
        @Positive int expectedRecipientCount) {
}
