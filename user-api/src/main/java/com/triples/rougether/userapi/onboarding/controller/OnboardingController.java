package com.triples.rougether.userapi.onboarding.controller;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.onboarding.dto.OnboardingCharacterRequest;
import com.triples.rougether.userapi.onboarding.dto.OnboardingCharacterResponse;
import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsRequest;
import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsResponse;
import com.triples.rougether.userapi.onboarding.dto.OnboardingResponse;
import com.triples.rougether.userapi.onboarding.service.OnboardingCharacterService;
import com.triples.rougether.userapi.onboarding.service.OnboardingGoalService;
import com.triples.rougether.userapi.onboarding.service.OnboardingQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Onboarding", description = "온보딩 관련 API")
@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

    private final OnboardingGoalService onboardingGoalService;
    private final OnboardingCharacterService onboardingCharacterService;
    private final OnboardingQueryService onboardingQueryService;

    public OnboardingController(OnboardingGoalService onboardingGoalService,
                               OnboardingCharacterService onboardingCharacterService,
                               OnboardingQueryService onboardingQueryService) {
        this.onboardingGoalService = onboardingGoalService;
        this.onboardingCharacterService = onboardingCharacterService;
        this.onboardingQueryService = onboardingQueryService;
    }

    @Operation(summary = "온보딩 상태 조회", description = "선택한 목표·대표 캐릭터와 온보딩 완료 여부를 조회합니다.")
    @GetMapping
    public OnboardingResponse getOnboarding(@CurrentUser AuthUser authUser) {
        return onboardingQueryService.getOnboarding(authUser.id());
    }

    @Operation(summary = "온보딩 목표 선택 저장", description = "온보딩에서 선택한 목표를 전체 교체 방식으로 저장합니다.")
    @PutMapping("/goals")
    public OnboardingGoalsResponse replaceGoals(@CurrentUser AuthUser authUser,
                                                @RequestBody OnboardingGoalsRequest request) {
        return onboardingGoalService.replaceGoals(authUser.id(), request);
    }

    @Operation(summary = "대표 캐릭터 선택·변경", description = "대표 캐릭터를 선택하거나 변경합니다.")
    @PutMapping("/character")
    public OnboardingCharacterResponse selectCharacter(@CurrentUser AuthUser authUser,
                                                       @Valid @RequestBody OnboardingCharacterRequest request) {
        return onboardingCharacterService.selectCharacter(authUser.id(), request.characterId());
    }
}
