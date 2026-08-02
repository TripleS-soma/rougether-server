package com.triples.rougether.userapi.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ProfileImageResponse(
        @Schema(description = "발급된 프로필 사진 asset key (CDN base URL 과 조합해 이미지 URL 로 사용). 내 정보 조회(GET /api/v1/me) 응답의 profileImageKey에도 반영", example = "profile/0b6f4c1e-8d1a-4c6e-9f2b-3a5d7e9c1b2d.png")
        String profileImageKey
) {
}
