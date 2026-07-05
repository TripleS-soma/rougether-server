package com.triples.rougether.userapi.shop.dto;

import com.triples.rougether.domain.shared.CurrencyType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// POST /api/v1/items/{id}/purchase 응답. 지급된 보유 아이템 + 차감 후 지갑 잔액 (spec shop/api.md).
public record PurchaseResponse(
        @Schema(description = "지급된 보유 아이템 ID (user_items). 방 슬롯 배치(PUT /api/v1/rooms/me/slots)의 "
                + "userItemId 값으로 사용", example = "77")
        Long userItemId,
        @Schema(description = "구매한 아이템 ID. GET /api/v1/items (상점 아이템 목록) 응답의 id 와 동일", example = "1")
        Long itemId,
        @Schema(description = "획득 시각")
        Instant acquiredAt,
        @Schema(description = "차감 반영 후 지갑 잔액")
        WalletSummary wallet) {

    public record WalletSummary(
            @Schema(description = "차감된 재화 종류. 허용값: COIN(코인 — 루틴 보상·뽑기), DIAMOND(다이아 — 상점 구매). "
                    + "상점 구매는 DIAMOND", example = "DIAMOND")
            CurrencyType currencyType,
            @Schema(description = "차감 후 잔액", example = "120")
            int balance) {
    }
}
