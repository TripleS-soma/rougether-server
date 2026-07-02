package com.triples.rougether.userapi.wallet.dto;

import com.triples.rougether.domain.shared.CurrencyType;
import java.util.List;

// GET /api/v1/me/wallets 응답. 모든 재화를 항상 내려주고, 지갑이 없는 재화는 잔액 0.
public record WalletListResponse(List<WalletResponse> items) {

    public record WalletResponse(CurrencyType currencyType, int balance) {
    }
}
