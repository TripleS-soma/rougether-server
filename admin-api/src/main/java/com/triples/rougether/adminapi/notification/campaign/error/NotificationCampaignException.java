package com.triples.rougether.adminapi.notification.campaign.error;

import lombok.Getter;

@Getter
public class NotificationCampaignException extends RuntimeException {
    private final String code;
    private final int status;

    public NotificationCampaignException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public static NotificationCampaignException invalid(String message) {
        return new NotificationCampaignException("VALIDATION_FAILED", message, 400);
    }
}
