package com.triples.rougether.userapi.wallet.dto;

import com.triples.rougether.domain.shared.CurrencyType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// GET /api/v1/me/wallets 응답. 모든 재화를 항상 내려주고, 지갑이 없는 재화는 잔액 0.
public record WalletListResponse(List<WalletResponse> items) {

    public record WalletResponse(
            @Schema(description = "재화 종류 (COIN=루틴 보상·뽑기, DIAMOND=상점 구매·중복 전환)", example = "COIN")
            CurrencyType currencyType,
            @Schema(description = "잔액 (지갑 미발급 재화는 0)", example = "5600")
            int balance) {
    }
}
