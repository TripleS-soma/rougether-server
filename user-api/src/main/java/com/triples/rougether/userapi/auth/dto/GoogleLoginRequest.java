package com.triples.rougether.userapi.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record GoogleLoginRequest(
        @Schema(description = "구글 로그인으로 프론트가 발급받은 ID token — 서버가 서명·발급자·대상·만료를 검증")
        @NotBlank
        String idToken,
        @Schema(description = "같은 이메일로 가입된 다른 소셜 계정이 있어도 새 계정 생성을 허용할지 — 409 AUTH_EMAIL_LINKED_TO_OTHER_PROVIDER 안내 뒤 사용자가 '새 계정으로 계속'을 고른 재요청에서만 true. 생략 시 false", example = "false")
        Boolean allowNewAccount
) {
    public boolean allowsNewAccount() {
        return Boolean.TRUE.equals(allowNewAccount);
    }
}
