package com.triples.rougether.userapi.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record TokenResponse(
        @Schema(description = "access token")
        String accessToken,
        @Schema(description = "refresh token")
        String refreshToken
) {
}
