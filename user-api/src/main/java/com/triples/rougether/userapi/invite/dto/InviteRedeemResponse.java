package com.triples.rougether.userapi.invite.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// POST /api/v1/invites/redeem 응답. 내가 받은 보상과 반영 후 코인 잔액.
public record InviteRedeemResponse(
        @Schema(description = "이번 사용으로 내가 받은 코인", example = "50")
        int rewardCoin,
        @Schema(description = "반영 후 내 코인 잔액", example = "150")
        int coinBalance,
        @Schema(description = "초대자에게도 보상이 지급됐는지. 초대자가 보상 한도를 넘겼으면 false", example = "true")
        boolean inviterRewarded) {
}
