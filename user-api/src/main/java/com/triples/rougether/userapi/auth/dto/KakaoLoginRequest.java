package com.triples.rougether.userapi.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record KakaoLoginRequest(
        @Schema(description = "카카오 SDK 로그인으로 프론트가 발급받은 access token — 서버가 카카오 API로 유효성을 검증")
        @NotBlank
        String accessToken
) {
}
