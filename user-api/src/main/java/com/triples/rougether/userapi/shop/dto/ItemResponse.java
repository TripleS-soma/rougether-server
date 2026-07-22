package com.triples.rougether.userapi.shop.dto;

import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

// 상점 아이템 응답. 이미지는 전체 URL 이 아니라 assetKey 로 내려주고, 프론트가 CDN base 와 조합(spec).
public record ItemResponse(
        @Schema(description = "아이템 ID. 구매 API(POST /api/v1/items/{itemId}/purchase)의 itemId 로 사용", example = "1")
        Long id,
        @Schema(description = "아이템 이름", example = "Bakery Morning Set - Breakfast Table")
        String name,
        @Schema(description = "이미지 asset key (CDN base URL 과 조합해 이미지 URL 로 사용)",
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
        @Schema(description = "새 자유배치의 초기 렌더링 배율 (0.50~2.00, 기존 배치 비소급)", example = "1.25")
        BigDecimal defaultScale,
        @Schema(description = "카테고리 코드", example = "furniture")
        String categoryCode,
        @Schema(description = "구매 재화. 허용값: COIN(코인 — 루틴·투두 보상으로 획득, 뽑기에 사용), "
                + "DIAMOND(다이아 — 상점 구매에 사용). 상점 판매 아이템은 DIAMOND. null 이면 뽑기 전용(구매 불가)",
                example = "DIAMOND")
        String purchaseCurrencyType,
        @Schema(description = "구매 가격 (purchaseCurrencyType 기준 차감액. null 이면 뽑기 전용으로 구매 불가)",
                example = "400")
        Integer priceAmount,
        @Schema(description = "한정 판매 여부 (표시용 플래그)", example = "false")
        boolean isLimited,
        @Schema(description = "소속 테마 정보")
        ThemeSummary theme,
        @Schema(description = "로그인한 회원의 보유 여부 (true 면 재구매 불가 — 구매 버튼 대신 보유 표시)",
                example = "false")
        boolean owned) {

    public record ThemeSummary(
            @Schema(description = "테마 ID. 상점 아이템 목록 조회의 themeId 필터 값으로 사용", example = "3")
            Long id,
            @Schema(description = "테마 코드", example = "bakery_morning")
            String code,
            @Schema(description = "테마 이름", example = "Bakery Morning Set")
            String name,
            @Schema(description = "테마 커버 이미지 asset key (CDN base URL 과 조합해 사용)",
                    example = "themes/bakery-morning-cover.png")
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
                item.getDefaultScale(),
                item.getCategoryCode(),
                item.getPurchaseCurrencyType() != null ? item.getPurchaseCurrencyType().name() : null,
                item.getPriceAmount(),
                item.isLimited(),
                new ThemeSummary(t.getId(), t.getCode(), t.getName(), t.getCoverImageKey()),
                owned);
    }
}
