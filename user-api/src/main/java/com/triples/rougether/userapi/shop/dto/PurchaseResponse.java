package com.triples.rougether.userapi.shop.dto;

import com.triples.rougether.domain.shared.CurrencyType;
import java.time.Instant;

// POST /api/v1/items/{id}/purchase 응답. 지급된 보유 아이템 + 차감 후 지갑 잔액 (spec shop/api.md).
public record PurchaseResponse(
        Long userItemId,
        Long itemId,
        Instant acquiredAt,
        WalletSummary wallet) {

    public record WalletSummary(CurrencyType currencyType, int balance) {
    }
}
