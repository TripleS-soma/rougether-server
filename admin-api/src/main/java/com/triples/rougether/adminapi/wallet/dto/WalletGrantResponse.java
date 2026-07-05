package com.triples.rougether.adminapi.wallet.dto;

import com.triples.rougether.domain.shared.CurrencyType;

// 지급 후 지갑 상태. created 는 이번 지급으로 지갑이 새로 발급됐는지.
public record WalletGrantResponse(
        Long userId,
        CurrencyType currencyType,
        int grantedAmount,
        int balance,
        boolean created) {
}
