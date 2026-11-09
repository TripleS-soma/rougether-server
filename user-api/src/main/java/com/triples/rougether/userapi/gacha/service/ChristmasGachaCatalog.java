package com.triples.rougether.userapi.gacha.service;

import java.util.Set;

// 승인된 크리스마스 에셋만 이벤트 뽑기에 포함하도록 asset key로 한정함.
public final class ChristmasGachaCatalog {
    private static final String PREFIX = "items/snowy-christmas-202611/";
    public static final Set<String> ASSET_KEYS = Set.of(
            PREFIX + "furniture/christmas-tree-animated-v1.webp",
            PREFIX + "furniture/stocking-fireplace-animated-v1.webp",
            PREFIX + "furniture/plaid-sofa-v1.png",
            PREFIX + "furniture/gift-stack-v1.png",
            PREFIX + "furniture/cocoa-table-animated-v1.webp",
            PREFIX + "furniture/snowy-window-animated-v1.webp",
            PREFIX + "furniture/christmas-wreath-v1.png",
            PREFIX + "furniture/winter-rug-v1.png",
            PREFIX + "wallpaper/snowflake-wallpaper-panel-free-v2.png",
            PREFIX + "floor/honey-winter-floor-v1.png");
    private ChristmasGachaCatalog() { }
}
