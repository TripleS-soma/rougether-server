package com.triples.rougether.userapi.market.dto;

import com.triples.rougether.domain.market.entity.CommandStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record MarketCommandResponse(
        @Schema(description = "접수 ID", example = "10")
        Long commandId,
        @Schema(description = "접수 상태. 허용값: PENDING(처리 대기), APPLIED(처리됨), REJECTED(거절됨)", example = "APPLIED")
        CommandStatus status,
        @Schema(description = "거절 사유(REJECTED일 때만). 허용값: MARKET_SELF_TRADE(내 주문끼리 맞물림), MARKET_ASSET_SUSPENDED(거래 정지), "
                + "MARKET_ORDER_NOT_OPEN(취소 대상이 이미 끝남), MARKET_ENGINE_ERROR(처리 반복 실패). 거절되면 맡긴 코인·가구는 돌려받음",
                example = "MARKET_SELF_TRADE", nullable = true)
        String rejectCode,
        @Schema(description = "주문 접수가 처리되어 만들어진 주문. 처리 전이거나 취소 접수면 null", nullable = true)
        MarketOrderResponse order
) {
}
