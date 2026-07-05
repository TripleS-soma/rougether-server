package com.triples.rougether.adminapi.wallet.dto;

import com.triples.rougether.domain.shared.CurrencyType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// POST /admin/users/{userId}/wallets/grant 요청. 개발/QA용 재화 지급.
public record WalletGrantRequest(
        @NotNull CurrencyType currencyType,
        @NotNull @Min(1) @Max(1_000_000) Integer amount) {
}
