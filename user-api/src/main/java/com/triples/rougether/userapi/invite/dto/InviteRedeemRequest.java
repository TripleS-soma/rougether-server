package com.triples.rougether.userapi.invite.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

// POST /api/v1/invites/redeem 요청.
public record InviteRedeemRequest(
        @Schema(description = "친구에게 받은 개인 초대코드 (대소문자 구분 없이 입력받아 서버가 대문자로 정규화)",
                example = "ABCD2345")
        @NotBlank(message = "초대코드를 입력해 주세요.")
        String code) {
}
