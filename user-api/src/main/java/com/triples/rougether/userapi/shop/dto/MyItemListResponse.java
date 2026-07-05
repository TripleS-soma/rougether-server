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
            @Schema(description = "보유 아이템 ID (user_items). 방 슬롯 배치(PUT /api/v1/rooms/me/slots)의 "
                    + "userItemId 값으로 사용", example = "77")
            Long userItemId,
            @Schema(description = "아이템 ID. GET /api/v1/items (상점 아이템 목록) 응답의 id 와 동일", example = "1")
            Long itemId,
            @Schema(description = "아이템 이름", example = "Bakery Morning Set - Breakfast Table")
            String name,
            @Schema(description = "이미지 asset key (CDN base URL 과 조합해 이미지 URL 로 사용)",
                    example = "items/bakery-morning/furniture/bakery-morning-breakfast-table.png")
            String assetKey,
            @Schema(description = "카테고리 코드", example = "furniture")
            String categoryCode,
            @Schema(description = "배치 방식 (positioned=가구/소품, surface_slot=벽지/바닥/배경)", example = "positioned")
            String placementType,
            @Schema(description = "surface 슬롯 종류 (wallpaper/floor/background, positioned 는 null)",
                    example = "wallpaper")
            String surfaceSlotType,
            @Schema(description = "캐릭터 악세사리 슬롯 종류 (해당 없으면 null)")
            String characterSlotType,
            @Schema(description = "positioned 가구의 기본 배치 슬롯 (topLeft/topCenter/topRight/midLeft/midRight/"
                    + "bottomLeft/bottomCenter/bottomRight, surface 아이템은 null)", example = "bottomCenter")
            String defaultSlot,
            @Schema(description = "소속 테마 정보")
            ItemResponse.ThemeSummary theme,
            @Schema(description = "획득 시각 (상점 구매·뽑기 지급 시각. 목록은 이 값 내림차순 정렬)")
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
