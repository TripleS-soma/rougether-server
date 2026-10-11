package com.triples.rougether.userapi.market.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

// 발행 재고 판매 주문 수량 수정 접수(#433)
public record AmendOrderRequest(
        @Schema(description = "요청 ID(UUID). 같은 값으로 다시 요청하면 기존 수정 접수를 돌려줌", example = "8a1c2d3e-4f5a-4b6c-8d7e-9f0a1b2c3d4e")
        @NotNull @Pattern(regexp = PlaceOrderRequest.UUID_PATTERN) String requestId,
        @Schema(description = "수정 후 주문 전체 수량(이미 팔린 수량 포함). 1~10, 지금 수량과 달라야 하고 팔린 수량보다 커야 함", example = "5")
        @NotNull @Min(1) @Max(10) Integer quantity
) {
}
