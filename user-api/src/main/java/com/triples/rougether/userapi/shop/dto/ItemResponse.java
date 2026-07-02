package com.triples.rougether.userapi.shop.dto;

import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;

// 상점 아이템 응답. 이미지는 전체 URL 이 아니라 assetKey 로 내려주고, 프론트가 CDN base 와 조합(spec).
public record ItemResponse(
        Long id,
        String name,
        String assetKey,
        String placementType,
        String surfaceSlotType,
        String characterSlotType,
        String defaultSlot,
        String categoryCode,
        String purchaseCurrencyType,
        Integer priceAmount,
        boolean isLimited,
        ThemeSummary theme,
        boolean owned) {

    public record ThemeSummary(Long id, String code, String name, String coverImageKey) {
    }

    public static ItemResponse of(Item item, boolean owned) {
        Theme t = item.getTheme();
        return new ItemResponse(
                item.getId(),
                item.getName(),
                item.getAssetKey(),
                item.getPlacementType(),
                item.getSurfaceSlotType(),
                item.getCharacterSlotType(),
                item.getDefaultSlot(),
                item.getCategoryCode(),
                item.getPurchaseCurrencyType() != null ? item.getPurchaseCurrencyType().name() : null,
                item.getPriceAmount(),
                item.isLimited(),
                new ThemeSummary(t.getId(), t.getCode(), t.getName(), t.getCoverImageKey()),
                owned);
    }
}
