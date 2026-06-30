package com.triples.rougether.userapi.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record LoginResponse(
        @Schema(description = "회원 ID", example = "1")
        Long userId,
        @Schema(description = "access token")
        String accessToken,
        @Schema(description = "refresh token")
        String refreshToken,
        @Schema(description = "신규 가입 회원 여부", example = "false")
        boolean isNewUser
) {
}
