package com.triples.rougether.userapi.market.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CancelOrderRequest(
        @Schema(description = "요청 ID(UUID). 같은 값으로 다시 요청하면 기존 취소 접수를 돌려줌", example = "8a1c2d3e-4f5a-4b6c-8d7e-9f0a1b2c3d4e")
        @NotNull @Pattern(regexp = PlaceOrderRequest.UUID_PATTERN) String requestId
) {
}
