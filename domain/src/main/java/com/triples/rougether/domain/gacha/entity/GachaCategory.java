package com.triples.rougether.domain.gacha.entity;

import com.triples.rougether.domain.shop.entity.Item;
import java.util.Arrays;

// 공개 장식 뽑기는 테마와 관계없이 세 가지 실제 배치 유형으로 구분함.
public enum GachaCategory {
    WALLPAPER("wallpaper_gacha", "벽지 뽑기"),
    FLOOR("floor_gacha", "바닥 뽑기"),
    FURNITURE("furniture_gacha", "가구 뽑기");

    private final String code;
    private final String displayName;

    GachaCategory(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static GachaCategory fromCode(String code) {
        return Arrays.stream(values()).filter(category -> category.code.equals(code))
                .findFirst().orElse(null);
    }

    // 명칭이나 테마로 추측하지 않음. 러그는 positioned 가구이며 floor 슬롯과 구분함.
    // 어드민 입력 오류로 장식 풀에 악세사리/배경이 섞여도 미리보기와 실제 지급에서 제외함.
    public static GachaCategory fromItem(Item item) {
        if (item == null || item.getCharacterSlotType() != null
                || "character_accessory".equals(item.getCategoryCode())
                || "background".equals(item.getCategoryCode())) {
            return null;
        }
        if ("surface_slot".equals(item.getPlacementType())) {
            if ("wallpaper".equals(item.getSurfaceSlotType())) {
                return WALLPAPER;
            }
            if ("floor".equals(item.getSurfaceSlotType())) {
                return FLOOR;
            }
        }
        if ("positioned".equals(item.getPlacementType()) && item.getSurfaceSlotType() == null) {
            return FURNITURE;
        }
        return null;
    }
}
