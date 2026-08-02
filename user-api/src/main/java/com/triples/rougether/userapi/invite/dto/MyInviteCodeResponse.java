package com.triples.rougether.userapi.invite.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// GET /api/v1/invites/me 응답. 내 초대코드와 보상 현황.
public record MyInviteCodeResponse(
        @Schema(description = "내 개인 초대코드. 친구가 POST /api/v1/invites/redeem 에 넣으면 양쪽이 보상을 받는다",
                example = "ABCD2345")
        String code,
        @Schema(description = "이 코드로 실제 보상이 지급된 초대 건수. 한도 초과로 0원 지급된 건은 세지 않아 "
                + "maxRewardedCount 를 넘지 않는다", example = "3")
        long rewardedCount,
        @Schema(description = "초대 1건당 초대자가 받는 코인", example = "50")
        int inviterRewardCoin,
        @Schema(description = "초대 1건당 초대받은 사람이 받는 코인", example = "50")
        int inviteeRewardCoin,
        @Schema(description = "보상이 지급되는 초대 건수 상한. 넘겨도 초대는 되지만 초대자 몫은 0이 된다", example = "10")
        int maxRewardedCount) {
}
