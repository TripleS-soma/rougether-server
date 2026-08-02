package com.triples.rougether.adminapi.itemslot.dto;

import com.triples.rougether.domain.shop.entity.Item;

// 모바일 Room의 표면 레이어를 관리자 미리보기에 합성하기 위한 활성 카탈로그 항목.
public record RoomPreviewSurfaceRow(
        Long id,
        String name,
        String assetKey,
        Long themeId,
        String themeCode,
        String themeName,
        String surfaceSlotType) {

    public static RoomPreviewSurfaceRow of(Item item) {
        return new RoomPreviewSurfaceRow(
                item.getId(),
                item.getName(),
                item.getAssetKey(),
                item.getTheme().getId(),
                item.getTheme().getCode(),
                item.getTheme().getName(),
                item.getSurfaceSlotType());
    }
}
