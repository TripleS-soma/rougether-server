package com.triples.rougether.userapi.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record DevLoginRequest(
        @Schema(description = "로그인할 회원 ID", example = "1")
        Long userId
) {
}
