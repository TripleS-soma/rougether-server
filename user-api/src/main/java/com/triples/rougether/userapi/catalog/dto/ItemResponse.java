package com.triples.rougether.userapi.catalog.dto;

import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.PlacementType;

public record ItemResponse(
        Long id,
        String name,
        String assetKey,
        PlacementType placementType,
        String surfaceSlotType,
        String characterSlotType,
        String categoryCode,
        CurrencyType purchaseCurrencyType,
        Integer priceAmount,
        boolean limited,
        ItemThemeResponse theme,
        boolean owned) {

    public static ItemResponse from(Item item, boolean owned) {
        return new ItemResponse(
                item.getId(),
                item.getName(),
                item.getAssetKey(),
                item.getPlacementType(),
                item.getSurfaceSlotType(),
                item.getCharacterSlotType(),
                item.getCategoryCode(),
                item.getPurchaseCurrencyType(),
                item.getPriceAmount(),
                item.isLimited(),
                ItemThemeResponse.from(item.getTheme()),
                owned);
    }
}
