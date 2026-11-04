package com.triples.rougether.userapi.market.dto;

import com.triples.rougether.domain.market.entity.MarketPurchase;

public record MarketPurchaseResponse(Long purchaseId, Long assetId, Long userItemId, int price) {
    public static MarketPurchaseResponse from(MarketPurchase p) {
        return new MarketPurchaseResponse(p.getId(), p.getAssetId(), p.getUserItemId(), p.getPrice());
    }
}
