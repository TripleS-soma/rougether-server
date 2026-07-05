package com.triples.rougether.userapi.house.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// POST /api/v1/houses 응답 (spec house/api.md).
public record HouseCreateResponse(
        @Schema(description = "생성된 집 ID. 집 상세 조회·설정 수정 등 /api/v1/houses/{houseId} 경로에 사용", example = "1")
        Long houseId,
        @Schema(description = "집 주인(OWNER) 회원 ID", example = "7")
        Long ownerUserId,
        @Schema(description = "초대코드 (영대문자+숫자 8자, 혼동되는 I·O·L·0·1 제외, 발급 후 7일 유효). 초대코드 참여(POST /api/v1/houses/join-by-code)와 미리보기(GET /api/v1/houses/by-code/{inviteCode})에 사용, 재발급 시 무효화", example = "ABCD2345")
        String inviteCode,
        @Schema(description = "초대코드 만료 시각 (발급 후 7일)")
        Instant inviteExpiresAt) {
}
