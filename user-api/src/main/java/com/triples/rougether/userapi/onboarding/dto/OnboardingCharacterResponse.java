package com.triples.rougether.userapi.onboarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record OnboardingCharacterResponse(
        @Schema(description = "선택된 대표 캐릭터 ID — 내 방 조회(GET /api/v1/rooms/me) 응답의 대표 캐릭터에 즉시 반영", example = "1") Long selectedCharacterId) {
}
