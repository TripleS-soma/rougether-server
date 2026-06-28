package com.triples.rougether.adminapi.content.web;

import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.PlacementType;

public record AdminItemResponse(
        Long id,
        Long themeId,
        String themeCode,
        String themeName,
        String categoryCode,
        PlacementType placementType,
        String surfaceSlotType,
        String characterSlotType,
        String name,
        CurrencyType purchaseCurrencyType,
        Integer priceAmount,
        String assetKey,
        boolean limited,
        boolean active) {

    public static AdminItemResponse from(Item item) {
        return new AdminItemResponse(
                item.getId(),
                item.getTheme().getId(),
                item.getTheme().getCode(),
                item.getTheme().getName(),
                item.getCategoryCode(),
                item.getPlacementType(),
                item.getSurfaceSlotType(),
                item.getCharacterSlotType(),
                item.getName(),
                item.getPurchaseCurrencyType(),
                item.getPriceAmount(),
                item.getAssetKey(),
                item.isLimited(),
                item.isActive());
    }
}
