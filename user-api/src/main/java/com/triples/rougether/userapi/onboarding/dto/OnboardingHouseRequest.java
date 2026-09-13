package com.triples.rougether.userapi.onboarding.dto;

import com.triples.rougether.domain.onboarding.entity.OnboardingHouseChoice;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record OnboardingHouseRequest(
        @Schema(description = "좋아요: AUTO_JOIN, 괜찮아요: PERSONAL", example = "AUTO_JOIN")
        @NotNull OnboardingHouseChoice choice) {
}
