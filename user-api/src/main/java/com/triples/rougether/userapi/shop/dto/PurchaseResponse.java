package com.triples.rougether.userapi.shop.dto;

import com.triples.rougether.domain.shared.CurrencyType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// POST /api/v1/items/{id}/purchase 응답. 지급된 보유 아이템 + 차감 후 지갑 잔액 (spec shop/api.md).
public record PurchaseResponse(
        @Schema(description = "지급된 보유 아이템 ID (user_items)", example = "77")
        Long userItemId,
        @Schema(description = "구매한 아이템 ID", example = "1")
        Long itemId,
        @Schema(description = "획득 시각")
        Instant acquiredAt,
        WalletSummary wallet) {

    public record WalletSummary(
            @Schema(description = "차감된 재화 종류", example = "DIAMOND")
            CurrencyType currencyType,
            @Schema(description = "차감 후 잔액", example = "120")
            int balance) {
    }
}
