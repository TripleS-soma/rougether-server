package com.triples.rougether.userapi.onboarding.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.onboarding.dto.OnboardingCharacterRequest;
import com.triples.rougether.userapi.onboarding.dto.OnboardingCharacterResponse;
import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsRequest;
import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsResponse;
import com.triples.rougether.userapi.onboarding.dto.OnboardingResponse;
import com.triples.rougether.userapi.onboarding.service.OnboardingCommandService;
import com.triples.rougether.userapi.onboarding.service.OnboardingQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Onboarding", description = "온보딩 관련 API")
@RestController
@RequestMapping("/api/v1/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingCommandService onboardingCommandService;
    private final OnboardingQueryService onboardingQueryService;

    @Operation(summary = "온보딩 상태 조회", description = "선택한 목표·대표 캐릭터와 온보딩 완료 여부를 조회합니다. goals는 목표 마스터의 sortOrder 오름차순으로 반환합니다. completed는 목표를 1개 이상 저장하고 대표 캐릭터를 선택한 상태면 true입니다.")
    @GetMapping
    public OnboardingResponse getOnboarding(@CurrentUser AuthUser authUser) {
        return onboardingQueryService.getOnboarding(authUser.id());
    }

    @Operation(summary = "온보딩 목표 선택 저장", description = "온보딩에서 선택한 목표를 전체 교체 방식으로 저장합니다. 기존에 저장된 목표 선택은 모두 삭제되고 요청한 목록으로 대체됩니다. goalIds는 목표 마스터 목록 조회(GET /api/v1/goals) 응답의 id를 사용하며 활성 목표만 저장할 수 있고, 중복 id는 한 번만 저장됩니다. primaryGoalId를 지정하면 해당 목표를 대표 목표로 저장하며, 생략하면 대표 목표 없이 저장됩니다. 응답 goals는 sortOrder 오름차순으로 반환합니다.")
    @PutMapping("/goals")
    public OnboardingGoalsResponse replaceGoals(@CurrentUser AuthUser authUser,
                                                @RequestBody OnboardingGoalsRequest request) {
        return onboardingCommandService.replaceGoals(authUser.id(), request);
    }

    @Operation(summary = "대표 캐릭터 선택·변경", description = "대표 캐릭터를 선택하거나 변경합니다. characterId는 캐릭터 마스터 목록 조회(GET /api/v1/characters) 응답의 id를 사용하며 활성 캐릭터만 선택할 수 있습니다. 보유한 캐릭터가 없는 최초 선택이면 해당 캐릭터를 자동 지급하고 즉시 대표로 착용합니다. 이미 캐릭터를 보유한 경우에는 보유한 캐릭터만 대표로 변경할 수 있으며, 변경 시 기존 대표 캐릭터는 자동 해제됩니다. 변경된 대표 캐릭터는 내 방 조회(GET /api/v1/rooms/me) 응답에 즉시 반영됩니다.")
    @PutMapping("/character")
    public OnboardingCharacterResponse selectCharacter(@CurrentUser AuthUser authUser,
                                                       @Valid @RequestBody OnboardingCharacterRequest request) {
        return onboardingCommandService.selectCharacter(authUser.id(), request.characterId());
    }
}
