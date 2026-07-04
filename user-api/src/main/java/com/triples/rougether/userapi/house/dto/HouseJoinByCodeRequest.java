package com.triples.rougether.userapi.house.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

// POST /api/v1/houses/join-by-code 요청.
public record HouseJoinByCodeRequest(
        @Schema(description = "초대코드 (영대문자+숫자 8자)", example = "ABCD2345")
        @NotBlank String inviteCode) {
}
