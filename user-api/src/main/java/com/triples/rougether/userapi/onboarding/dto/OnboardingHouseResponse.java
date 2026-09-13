package com.triples.rougether.userapi.onboarding.dto;

import com.triples.rougether.domain.onboarding.entity.OnboardingHouseChoice;
import com.triples.rougether.domain.onboarding.entity.OnboardingHouseResult;
import com.triples.rougether.domain.onboarding.entity.OnboardingHouseSelection;
import io.swagger.v3.oas.annotations.media.Schema;

public record OnboardingHouseResponse(
        @Schema(description = "집 선택 처리 여부. 기존 온보딩 completed와 별개") boolean completed,
        OnboardingHouseChoice choice,
        @Schema(description = "JOINED: 자동 합류, PERSONAL: 개인집 선택, NO_MATCH: 후보 없음으로 개인집 시작")
        OnboardingHouseResult result,
        @Schema(description = "선택 처리 당시 시작할 집 ID. 이후 탈퇴·해체 여부는 내 집 목록으로 확인") Long houseId,
        Long membershipId) {
    public static OnboardingHouseResponse pending() {
        return new OnboardingHouseResponse(false, null, null, null, null);
    }

    public static OnboardingHouseResponse of(OnboardingHouseSelection selection) {
        return new OnboardingHouseResponse(true, selection.getChoice(), selection.getResult(),
                selection.getMembership().getHouse().getId(), selection.getMembership().getId());
    }
}
