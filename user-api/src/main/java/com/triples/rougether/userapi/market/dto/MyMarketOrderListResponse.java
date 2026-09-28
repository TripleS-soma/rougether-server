package com.triples.rougether.userapi.market.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// GET /api/v1/me/market/orders 응답. 최신 주문부터.
public record MyMarketOrderListResponse(
        List<MarketOrderResponse> items,
        @Schema(description = "페이지 번호 (0부터)", example = "0")
        int page,
        @Schema(description = "페이지 크기", example = "20")
        int size,
        @Schema(description = "조건에 맞는 전체 주문 수", example = "3")
        long totalElements) {
}
