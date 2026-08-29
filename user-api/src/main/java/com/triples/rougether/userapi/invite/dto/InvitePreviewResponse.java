package com.triples.rougether.userapi.invite.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// GET /api/v1/invites/by-code/{code} 응답 - 초대코드 사용(redeem) 전 미리보기.
// 딥링크·클립보드로 자동 입력된 코드를 "OO님의 초대" 확인 화면으로 검증하는 용도다.
// 자동 '사용'은 금지 계약: redeem 은 계정당 평생 1회라, 이 응답으로 사용자 확인을 받은 뒤에만 redeem 을 호출한다.
public record InvitePreviewResponse(
        @Schema(description = "초대자 닉네임. 온보딩 전 미설정 계정은 null - 화면에서 '친구' 등으로 폴백", example = "소마",
                nullable = true)
        String inviterNickname,
        @Schema(description = "이 코드를 사용하면 초대받은 사람이 받는 코인", example = "50")
        int inviteeRewardCoin,
        @Schema(description = "true 면 이 계정은 이미 초대 보상을 받아 redeem 이 409 로 거절된다 - 확인 화면을 건너뛴다",
                example = "false")
        boolean alreadyRedeemed) {
}
