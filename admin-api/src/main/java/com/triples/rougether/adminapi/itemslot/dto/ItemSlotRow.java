package com.triples.rougether.adminapi.itemslot.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.triples.rougether.adminapi.asset.AssetKeyClassifier;
import com.triples.rougether.domain.gacha.entity.GachaPoolEntry;
import com.triples.rougether.domain.shop.entity.Item;
import java.util.List;
import java.util.Objects;

// 슬롯 편집 화면의 한 행. 이미지는 assetKey 로 내려주고 화면 JS 가 base URL 과 조합.
public record ItemSlotRow(
        Long id,
        String name,
        String assetKey,
        String themeName,
        String defaultSlot,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String rarity,
        boolean rarityEditable,
        boolean rarityConflict,
        boolean animated) {

    public static ItemSlotRow of(Item item, List<GachaPoolEntry> activeItemEntries) {
        String firstRarity = activeItemEntries.isEmpty() ? null : activeItemEntries.getFirst().getRarity();
        boolean rarityConflict = activeItemEntries.stream()
                .anyMatch(entry -> !Objects.equals(firstRarity, entry.getRarity()));
        String rarity = rarityConflict ? null : firstRarity;
        return new ItemSlotRow(
                item.getId(),
                item.getName(),
                item.getAssetKey(),
                item.getTheme().getName(),
                item.getDefaultSlot(),
                rarity,
                !activeItemEntries.isEmpty(),
                rarityConflict,
                AssetKeyClassifier.isAnimated(item.getAssetKey()));
    }
}
