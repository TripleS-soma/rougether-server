package com.triples.rougether.userapi.house.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// POST /api/v1/houses/{houseId}/invite-code 응답. 재발급 즉시 기존 코드는 무효.
public record InviteCodeResponse(
        @Schema(description = "새 초대코드 (영대문자+숫자 8자, 혼동되는 I·O·L·0·1 제외, 재발급 후 7일 유효). 재발급 즉시 기존 코드는 무효. 초대코드 참여(POST /api/v1/houses/join-by-code)와 미리보기(GET /api/v1/houses/by-code/{inviteCode})에 사용", example = "WXYZ6789")
        String inviteCode,
        @Schema(description = "초대코드 만료 시각 (재발급 후 7일)")
        Instant inviteExpiresAt) {
}
