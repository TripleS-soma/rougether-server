package com.triples.rougether.userapi.shop.dto;

import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.entity.UserItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

// GET /api/v1/me/items 응답. 인벤토리(보유 아이템) - 최근 획득 먼저.
public record MyItemListResponse(List<MyItemSummary> items) {

    public record MyItemSummary(
            @Schema(description = "보유 아이템 ID (user_items)", example = "77")
            Long userItemId,
            @Schema(description = "아이템 ID", example = "1")
            Long itemId,
            @Schema(description = "아이템 이름", example = "Bakery Morning Set - Breakfast Table")
            String name,
            @Schema(description = "이미지 asset key",
                    example = "items/bakery-morning/furniture/bakery-morning-breakfast-table.png")
            String assetKey,
            @Schema(description = "카테고리 코드", example = "furniture")
            String categoryCode,
            @Schema(description = "배치 방식 (positioned/surface_slot)", example = "positioned")
            String placementType,
            @Schema(description = "surface 슬롯 종류 (positioned 는 null)", example = "wallpaper")
            String surfaceSlotType,
            @Schema(description = "캐릭터 악세사리 슬롯 종류 (해당 없으면 null)")
            String characterSlotType,
            @Schema(description = "positioned 가구의 기본 배치 슬롯", example = "bottomCenter")
            String defaultSlot,
            ItemResponse.ThemeSummary theme,
            @Schema(description = "획득 시각")
            Instant acquiredAt) {

        public static MyItemSummary of(UserItem userItem) {
            Item item = userItem.getItem();
            Theme theme = item.getTheme();
            return new MyItemSummary(
                    userItem.getId(),
                    item.getId(),
                    item.getName(),
                    item.getAssetKey(),
                    item.getCategoryCode(),
                    item.getPlacementType(),
                    item.getSurfaceSlotType(),
                    item.getCharacterSlotType(),
                    item.getDefaultSlot(),
                    new ItemResponse.ThemeSummary(theme.getId(), theme.getCode(), theme.getName(),
                            theme.getCoverImageKey()),
                    userItem.getAcquiredAt());
        }
    }
}
