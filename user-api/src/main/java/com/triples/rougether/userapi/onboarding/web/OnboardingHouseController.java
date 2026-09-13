package com.triples.rougether.userapi.onboarding.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.onboarding.dto.OnboardingHouseRequest;
import com.triples.rougether.userapi.onboarding.dto.OnboardingHouseResponse;
import com.triples.rougether.userapi.onboarding.service.OnboardingHouseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Onboarding")
@RestController
@RequestMapping("/api/v1/onboarding/house")
@RequiredArgsConstructor
public class OnboardingHouseController {
    private final OnboardingHouseService service;

    @Operation(summary = "온보딩 집 선택 결과 조회", description = "아직 선택하지 않았으면 completed=false입니다. 기존 목표·캐릭터 온보딩 완료 기준과 독립적입니다.")
    @GetMapping
    public OnboardingHouseResponse get(@CurrentUser AuthUser user) {
        return service.get(user.id());
    }

    @Operation(summary = "온보딩 집 선택", description = "AUTO_JOIN(좋아요)은 자동 입주를 허용한 공개 집에 합류합니다. "
            + "후보가 없으면 개인집을 공개·자동 입주 허용으로 바꾸고 NO_MATCH를 반환합니다. "
            + "PERSONAL(괜찮아요)은 비공개 개인집으로 시작합니다. 기존 개인집은 합류 후에도 유지합니다. "
            + "같은 선택 재요청은 최초 결과를 반환하고, 완료 후 다른 선택은 409입니다.")
    @PutMapping
    public OnboardingHouseResponse select(@CurrentUser AuthUser user, @Valid @RequestBody OnboardingHouseRequest request) {
        return service.select(user.id(), request.choice());
    }
}
