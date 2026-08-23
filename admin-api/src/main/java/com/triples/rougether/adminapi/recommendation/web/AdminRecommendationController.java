package com.triples.rougether.adminapi.recommendation.web;

import com.triples.rougether.adminapi.recommendation.dto.AdminRecommendationMetricsResponse;
import com.triples.rougether.adminapi.recommendation.service.AdminRecommendationMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// AI 조정 추천 퍼널 관측 API(#332). 관측 화면(/recommendations)의 JS 가 호출한다.
@RestController
@RequestMapping("/admin/recommendations")
public class AdminRecommendationController {

    private final AdminRecommendationMetricsService adminRecommendationMetricsService;

    public AdminRecommendationController(AdminRecommendationMetricsService adminRecommendationMetricsService) {
        this.adminRecommendationMetricsService = adminRecommendationMetricsService;
    }

    @GetMapping("/metrics")
    public AdminRecommendationMetricsResponse getMetrics(
            @RequestParam(defaultValue = "" + AdminRecommendationMetricsService.DEFAULT_WEEKS) int weeks) {
        return adminRecommendationMetricsService.getMetrics(weeks);
    }
}
