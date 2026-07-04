package com.triples.rougether.userapi.onboarding.web;

import com.triples.rougether.userapi.onboarding.dto.GoalListResponse;
import com.triples.rougether.userapi.onboarding.service.OnboardingQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Onboarding", description = "온보딩 관련 API")
@RestController
@RequestMapping("/api/v1/goals")
@RequiredArgsConstructor
public class GoalController {

    private final OnboardingQueryService onboardingQueryService;

    @Operation(summary = "목표 마스터 목록 조회", description = "온보딩에 필요한 활성 목표 마스터 목록을 조회합니다.")
    @GetMapping
    public GoalListResponse getGoals() {
        return onboardingQueryService.getGoals();
    }
}
