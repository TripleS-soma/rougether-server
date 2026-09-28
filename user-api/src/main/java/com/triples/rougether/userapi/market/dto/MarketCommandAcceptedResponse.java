package com.triples.rougether.userapi.market.dto;

import com.triples.rougether.domain.market.entity.CommandStatus;
import com.triples.rougether.domain.market.entity.MarketCommand;
import io.swagger.v3.oas.annotations.media.Schema;

public record MarketCommandAcceptedResponse(
        @Schema(description = "접수 ID. 접수 결과 조회(GET /api/v1/market/commands/{commandId})의 {commandId}로 사용", example = "10")
        Long commandId,
        @Schema(description = "접수 상태. 허용값: PENDING(처리 대기), APPLIED(처리됨), REJECTED(거절됨). "
                + "새 접수는 PENDING이며, 같은 requestId 재요청이면 이미 처리된 상태가 올 수 있음", example = "PENDING")
        CommandStatus status
) {

    public static MarketCommandAcceptedResponse from(MarketCommand command) {
        return new MarketCommandAcceptedResponse(command.getId(), command.getStatus());
    }
}
