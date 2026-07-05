package com.triples.rougether.userapi.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record TokenResponse(
        @Schema(description = "재발급된 access token — 이후 API 호출 시 Authorization: Bearer 헤더에 사용. 유효기간 30분")
        String accessToken,
        @Schema(description = "재발급된 refresh token — 다음 재발급·로그아웃 요청에 사용. 유효기간 14일, 1회용")
        String refreshToken
) {
}
