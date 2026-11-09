package com.triples.rougether.adminapi.notification.campaign.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "admin.notification-campaigns.dispatch-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationCampaignSchedulingConfiguration {
}
