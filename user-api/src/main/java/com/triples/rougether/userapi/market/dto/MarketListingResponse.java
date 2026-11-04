package com.triples.rougether.userapi.market.dto;

import java.util.List;

// 고정가 상품. 수량이나 주문 대기 없이 가격과 보유 여부만 표시함.
public record MarketListingResponse(Long assetId, Long itemId, String name, String assetKey,
        String creatorNickname, boolean isCreator, Integer price, boolean selling, boolean owned) {
    public record Listings(List<MarketListingResponse> items, int page, int size, long totalElements) { }
}
