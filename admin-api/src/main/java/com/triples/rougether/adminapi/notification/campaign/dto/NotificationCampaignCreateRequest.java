package com.triples.rougether.adminapi.notification.campaign.dto;

import com.triples.rougether.domain.notification.campaign.entity.NotificationCampaign.Audience;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public record NotificationCampaignCreateRequest(
        @NotNull UUID requestId,
        @NotBlank @Size(max = 255) String title,
        @NotBlank @Size(max = 1000) String body,
        @NotNull Audience audience,
        @Size(max = 1000) List<@NotNull @Positive Long> userIds,
        boolean pushEnabled) {
}
