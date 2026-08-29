package com.triples.rougether.adminapi.retention.web;

import com.triples.rougether.adminapi.retention.dto.AdminRetentionMetricsResponse;
import com.triples.rougether.adminapi.retention.service.AdminRetentionMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/retention")
public class AdminRetentionController {

    private final AdminRetentionMetricsService adminRetentionMetricsService;

    public AdminRetentionController(AdminRetentionMetricsService adminRetentionMetricsService) {
        this.adminRetentionMetricsService = adminRetentionMetricsService;
    }

    @GetMapping("/metrics")
    public AdminRetentionMetricsResponse getMetrics(
            @RequestParam(defaultValue = "" + AdminRetentionMetricsService.DEFAULT_COHORT_DAYS) int cohortDays) {
        return adminRetentionMetricsService.getMetrics(cohortDays);
    }
}
