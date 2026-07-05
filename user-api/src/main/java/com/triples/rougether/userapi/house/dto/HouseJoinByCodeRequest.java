package com.triples.rougether.userapi.house.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

// POST /api/v1/houses/join-by-code 요청.
public record HouseJoinByCodeRequest(
        @Schema(description = "초대코드 (영대문자+숫자 8자, 발급 후 7일 유효, 만료 전 코드만 사용 가능). 집 생성(POST /api/v1/houses) 응답 또는 집 상세(GET /api/v1/houses/{houseId}) 응답(소유자)의 inviteCode 값, 재발급은 POST /api/v1/houses/{houseId}/invite-code", example = "ABCD2345")
        @NotBlank String inviteCode) {
}
