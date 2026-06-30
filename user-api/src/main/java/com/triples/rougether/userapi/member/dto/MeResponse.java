package com.triples.rougether.userapi.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record MeResponse(
        @Schema(description = "회원 ID", example = "1")
        Long userId,
        @Schema(description = "닉네임", example = "루티니")
        String nickname,
        @Schema(description = "마지막 로그인 시각")
        OffsetDateTime lastLoginAt
) {
}
