package com.triples.rougether.userapi.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(
        @Schema(description = "재발급에 사용할 refresh token (로그인/재발급 응답의 refreshToken 값) — 1회용으로, 사용 즉시 폐기됨")
        @NotBlank String refreshToken
) {
}
