package com.triples.rougether.adminapi.catalog.dto;

import com.triples.rougether.domain.shop.entity.Item;

// 카탈로그 화면의 아이템 한 행. 이미지는 assetKey 로 내려주고 화면 JS 가 base URL 과 조합.
// themeActive 를 함께 내리는 이유: 아이템이 활성이어도 테마가 비활성이면 상점·뽑기에 노출되지 않으므로
// 화면에서 "테마 비활성" 경고를 보여줘야 한다.
public record CatalogItemRow(
        Long id,
        String name,
        String assetKey,
        String themeCode,
        String themeName,
        boolean themeActive,
        String categoryCode,
        String placementType,
        String purchaseCurrencyType,
        Integer priceAmount,
        boolean limited,
        boolean active) {

    public static CatalogItemRow of(Item item) {
        return new CatalogItemRow(
                item.getId(),
                item.getName(),
                item.getAssetKey(),
                item.getTheme().getCode(),
                item.getTheme().getName(),
                item.getTheme().isActive(),
                item.getCategoryCode(),
                item.getPlacementType(),
                item.getPurchaseCurrencyType() == null ? null : item.getPurchaseCurrencyType().name(),
                item.getPriceAmount(),
                item.isLimited(),
                item.isActive());
    }
}
