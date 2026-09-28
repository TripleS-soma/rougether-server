package com.triples.rougether.userapi.market.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record MarketAssetIssueRequest(
        @Schema(description = "발행할 내 AI 가구의 보유 아이템 ID. GET /api/v1/me/items 응답의 userItemId 값", example = "77")
        @NotNull Long userItemId,
        @Schema(description = "발행 수량(1~10). 내가 가진 1개를 포함하며 나머지는 발행 재고가 됨. 발행 후 변경·추가 발행 불가", example = "5")
        @NotNull @Min(1) @Max(10) Integer totalSupply
) {
}
