package com.triples.rougether.userapi.moderation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record BlockedUserResponse(
        @Schema(description = "차단한 회원 ID", example = "8")
        Long userId,
        @Schema(description = "닉네임. 없으면 null", example = "이웃", nullable = true)
        String nickname,
        @Schema(description = "프로필 이미지 asset key. 없으면 null", nullable = true)
        String profileImageKey,
        @Schema(description = "차단 시각(UTC)", example = "2026-09-29T03:00:00Z")
        Instant blockedAt) {
}
