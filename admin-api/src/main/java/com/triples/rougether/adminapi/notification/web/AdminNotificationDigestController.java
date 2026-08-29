package com.triples.rougether.adminapi.notification.web;

import com.triples.rougether.adminapi.notification.dto.AdminNotificationDigestMetricsResponse;
import com.triples.rougether.adminapi.notification.service.AdminNotificationDigestMetricsService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/admin/notification-digests")
public class AdminNotificationDigestController {

    private final AdminNotificationDigestMetricsService metricsService;

    public AdminNotificationDigestController(AdminNotificationDigestMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/metrics")
    public AdminNotificationDigestMetricsResponse getMetrics(
            @RequestParam(defaultValue = "" + AdminNotificationDigestMetricsService.DEFAULT_DAYS)
            @Min(1) @Max(AdminNotificationDigestMetricsService.MAX_DAYS) int days) {
        return metricsService.getMetrics(days);
    }
}
