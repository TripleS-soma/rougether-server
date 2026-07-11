package com.triples.rougether.userapi.member.dto;

import com.triples.rougether.userapi.onboarding.dto.OnboardingSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record MeResponse(
        @Schema(description = "회원 ID", example = "1")
        Long userId,
        @Schema(description = "닉네임 — 소셜 가입 시점에는 받지 않으므로 설정 전에는 null", example = "루티니")
        String nickname,
        @Schema(description = "한줄 소개 — 설정하지 않으면 null", example = "루틴을 사랑하는 사람")
        String bio,
        @Schema(description = "마지막 로그인 시각(UTC) — 로그인 성공 시 갱신되며 이력이 없으면 null", example = "2026-07-05T03:34:56Z")
        Instant lastLoginAt,
        @Schema(description = "온보딩 진행 요약 — completed로 온보딩 화면 진입 여부 판단에 사용")
        OnboardingSummary onboarding
) {
}
