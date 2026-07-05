package com.triples.rougether.userapi.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record LoginResponse(
        @Schema(description = "회원 ID", example = "1")
        Long userId,
        @Schema(description = "access token — 이후 API 호출 시 Authorization: Bearer 헤더에 사용. 유효기간 30분")
        String accessToken,
        @Schema(description = "refresh token — 토큰 재발급(POST /api/v1/auth/refresh)·로그아웃 요청에 사용. 유효기간 14일, 1회용(재발급에 사용하면 폐기되고 새 토큰으로 교체됨)")
        String refreshToken,
        @Schema(description = "신규 가입 회원 여부 — true면 이번 로그인에서 회원이 새로 생성됨(온보딩 진입 판단에 사용)", example = "false")
        boolean isNewUser
) {
}
