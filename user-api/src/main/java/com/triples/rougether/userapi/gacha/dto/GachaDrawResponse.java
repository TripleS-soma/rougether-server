package com.triples.rougether.userapi.gacha.dto;

import com.triples.rougether.domain.shared.CurrencyType;
import java.util.List;

// POST /api/v1/gacha/{id}/draw 응답. 뽑은 결과 목록 + 차감/환급 후 지갑 잔액.
public record GachaDrawResponse(List<DrawResult> results, WalletSummary wallet) {

    // 한 번의 뽑기 결과. converted=true 면 중복이라 아이템 대신 COIN 환급.
    public record DrawResult(
            String rewardType,
            Long itemId,
            String name,
            String assetKey,
            String rarity,
            boolean converted,
            Integer refundAmount) {
    }

    public record WalletSummary(CurrencyType currencyType, int balance) {
    }
}
