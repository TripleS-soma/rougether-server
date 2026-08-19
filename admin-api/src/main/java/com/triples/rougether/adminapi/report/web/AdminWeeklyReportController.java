package com.triples.rougether.adminapi.report.web;

import com.triples.rougether.adminapi.report.dto.AdminWeeklyReportMetricsResponse;
import com.triples.rougether.adminapi.report.service.AdminWeeklyReportMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// AI 주간 회고 생성 관측 API. 관측 화면(/weekly-reports)의 JS 가 호출한다.
@RestController
@RequestMapping("/admin/weekly-reports")
public class AdminWeeklyReportController {

    private final AdminWeeklyReportMetricsService adminWeeklyReportMetricsService;

    public AdminWeeklyReportController(AdminWeeklyReportMetricsService adminWeeklyReportMetricsService) {
        this.adminWeeklyReportMetricsService = adminWeeklyReportMetricsService;
    }

    @GetMapping("/metrics")
    public AdminWeeklyReportMetricsResponse getMetrics(
            @RequestParam(defaultValue = "" + AdminWeeklyReportMetricsService.DEFAULT_WEEKS) int weeks) {
        return adminWeeklyReportMetricsService.getMetrics(weeks);
    }
}
