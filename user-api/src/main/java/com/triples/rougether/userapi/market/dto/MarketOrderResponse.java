package com.triples.rougether.userapi.market.dto;

import com.triples.rougether.domain.market.entity.MarketOrder;
import com.triples.rougether.domain.market.entity.OrderSide;
import com.triples.rougether.domain.market.entity.OrderSource;
import com.triples.rougether.domain.market.entity.OrderStatus;
import com.triples.rougether.domain.shop.entity.Item;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record MarketOrderResponse(
        @Schema(description = "주문 ID. 주문 취소(POST /api/v1/market/orders/{orderId}/cancel)의 {orderId}로 사용", example = "5")
        Long orderId,
        @Schema(description = "종목 ID", example = "1")
        Long assetId,
        @Schema(description = "가구 이름", example = "고양이 소파")
        String name,
        @Schema(description = "가구 이미지 asset key. CDN base URL과 조합해 사용", example = "furniture/photo/abc.png")
        String assetKey,
        @Schema(description = "주문 방향. 허용값: BUY(구매), SELL(판매)", example = "BUY")
        OrderSide side,
        @Schema(description = "판매 물량 출처(판매 주문만). 허용값: INVENTORY(내가 가진 가구), ISSUANCE(발행 재고)", example = "INVENTORY", nullable = true)
        OrderSource source,
        @Schema(description = "개당 주문 가격(코인)", example = "30")
        int price,
        @Schema(description = "주문 수량", example = "1")
        int quantity,
        @Schema(description = "체결된 수량", example = "0")
        int filledQuantity,
        @Schema(description = "주문 상태. 허용값: OPEN(대기), FILLED(전부 체결), CANCELLED(취소), EXPIRED(7일 만료)", example = "OPEN")
        OrderStatus status,
        @Schema(description = "자동 만료 시각(접수 후 7일)")
        Instant expiresAt,
        @Schema(description = "주문 생성 시각")
        Instant createdAt
) {

    public static MarketOrderResponse of(MarketOrder order, Item item) {
        return new MarketOrderResponse(order.getId(), order.getAssetId(), item.getName(), item.getAssetKey(),
                order.getSide(), order.getSource(), order.getPrice(), order.getQuantity(), order.getFilledQuantity(),
                order.getStatus(), order.getExpiresAt(), order.getCreatedAt());
    }
}
