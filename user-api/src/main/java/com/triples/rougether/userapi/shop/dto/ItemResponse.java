package com.triples.rougether.userapi.shop.dto;

import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import io.swagger.v3.oas.annotations.media.Schema;

// 상점 아이템 응답. 이미지는 전체 URL 이 아니라 assetKey 로 내려주고, 프론트가 CDN base 와 조합(spec).
public record ItemResponse(
        @Schema(description = "아이템 ID", example = "1")
        Long id,
        @Schema(description = "아이템 이름", example = "Bakery Morning Set - Breakfast Table")
        String name,
        @Schema(description = "이미지 asset key (CDN base 와 조합해 사용)",
                example = "items/bakery-morning/furniture/bakery-morning-breakfast-table.png")
        String assetKey,
        @Schema(description = "배치 방식 (positioned=가구/소품, surface_slot=벽지/바닥/배경)", example = "positioned")
        String placementType,
        @Schema(description = "surface 아이템의 슬롯 종류 (wallpaper/floor/background, positioned 는 null)",
                example = "wallpaper")
        String surfaceSlotType,
        @Schema(description = "캐릭터 악세사리 슬롯 종류 (해당 없으면 null)")
        String characterSlotType,
        @Schema(description = "positioned 가구의 기본 배치 슬롯 (topLeft/topCenter/topRight/midLeft/midRight/"
                + "bottomLeft/bottomCenter/bottomRight, surface 아이템은 null)", example = "bottomCenter")
        String defaultSlot,
        @Schema(description = "카테고리 코드", example = "furniture")
        String categoryCode,
        @Schema(description = "구매 재화 (DIAMOND, 뽑기 전용이면 null)", example = "DIAMOND")
        String purchaseCurrencyType,
        @Schema(description = "구매 가격 (뽑기 전용이면 null)", example = "400")
        Integer priceAmount,
        @Schema(description = "한정 판매 여부", example = "false")
        boolean isLimited,
        ThemeSummary theme,
        @Schema(description = "로그인한 회원의 보유 여부", example = "false")
        boolean owned) {

    public record ThemeSummary(
            @Schema(description = "테마 ID", example = "3")
            Long id,
            @Schema(description = "테마 코드", example = "bakery_morning")
            String code,
            @Schema(description = "테마 이름", example = "Bakery Morning Set")
            String name,
            @Schema(description = "테마 커버 이미지 asset key", example = "themes/bakery-morning-cover.png")
            String coverImageKey) {
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
