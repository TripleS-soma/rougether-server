package com.triples.rougether.userapi.onboarding.controller;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsRequest;
import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsResponse;
import com.triples.rougether.userapi.onboarding.service.OnboardingGoalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Onboarding", description = "온보딩 관련 API")
@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

    private final OnboardingGoalService onboardingGoalService;

    public OnboardingController(OnboardingGoalService onboardingGoalService) {
        this.onboardingGoalService = onboardingGoalService;
    }

    @Operation(summary = "온보딩 목표 선택 저장", description = "온보딩에서 선택한 목표를 전체 교체 방식으로 저장합니다.")
    @PutMapping("/goals")
    public OnboardingGoalsResponse replaceGoals(@CurrentUser AuthUser authUser,
                                                @RequestBody OnboardingGoalsRequest request) {
        return onboardingGoalService.replaceGoals(authUser.id(), request);
    }
}
