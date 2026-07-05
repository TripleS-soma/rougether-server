package com.triples.rougether.userapi.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record GoogleLoginRequest(
        @Schema(description = "구글 로그인으로 프론트가 발급받은 ID token — 서버가 서명·발급자·대상·만료를 검증")
        @NotBlank
        String idToken
) {
}
