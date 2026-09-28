package com.triples.rougether.userapi.market.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

// GET /api/v1/market/assets/{assetId}/trades 응답. 최신 체결부터.
public record MarketTradeListResponse(
        List<TradeItem> items,
        @Schema(description = "페이지 번호 (0부터)", example = "0")
        int page,
        @Schema(description = "페이지 크기", example = "20")
        int size,
        @Schema(description = "전체 체결 수", example = "12")
        long totalElements) {

    public record TradeItem(
            @Schema(description = "체결 ID", example = "7")
            Long tradeId,
            @Schema(description = "체결 가격(코인)", example = "30")
            int price,
            @Schema(description = "체결 수량", example = "1")
            int quantity,
            @Schema(description = "체결 시각")
            Instant tradedAt) {
    }
}
