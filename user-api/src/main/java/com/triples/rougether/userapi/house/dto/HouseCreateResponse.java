package com.triples.rougether.userapi.house.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// POST /api/v1/houses 응답 (spec house/api.md).
public record HouseCreateResponse(
        @Schema(description = "생성된 집 ID", example = "1")
        Long houseId,
        @Schema(description = "집 주인(OWNER) 회원 ID", example = "7")
        Long ownerUserId,
        @Schema(description = "초대코드 (영대문자+숫자 8자)", example = "ABCD2345")
        String inviteCode,
        @Schema(description = "초대코드 만료 시각 (발급 후 7일)")
        Instant inviteExpiresAt) {
}
