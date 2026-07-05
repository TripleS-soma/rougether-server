package com.triples.rougether.userapi.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record DevLoginRequest(
        @Schema(description = "로그인할 회원 ID (개발용 — 임의 회원 id 지정). 생략(null)하면 새 회원을 생성해 로그인", example = "1")
        Long userId
) {
}
