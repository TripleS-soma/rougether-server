package com.triples.rougether.adminapi.itemslot.dto;

import com.triples.rougether.domain.shop.entity.Item;

// 슬롯 편집 화면의 한 행. 이미지는 assetKey 로 내려주고 화면 JS 가 base URL 과 조합.
public record ItemSlotRow(
        Long id,
        String name,
        String assetKey,
        String themeName,
        String defaultSlot) {

    public static ItemSlotRow of(Item item) {
        return new ItemSlotRow(
                item.getId(),
                item.getName(),
                item.getAssetKey(),
                item.getTheme().getName(),
                item.getDefaultSlot());
    }
}
