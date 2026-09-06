package com.triples.rougether.domain.gacha;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.gacha.entity.GachaCategory;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GachaCategoryTest {

    @ParameterizedTest
    @CsvSource({
            "wallpaper,surface_slot,wallpaper,,WALLPAPER",
            "floor,surface_slot,floor,,FLOOR",
            "furniture,positioned,,,FURNITURE",
            "rug,positioned,,,FURNITURE"
    })
    void 테마와_무관하게_실제_배치_유형으로_분류한다(
            String category, String placement, String surface, String character, GachaCategory expected) {
        assertThat(GachaCategory.fromItem(item(category, placement, surface, character)))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "background,surface_slot,background,",
            "background,positioned,,",
            "character_accessory,character,,head",
            "character_accessory,positioned,,",
            "furniture,positioned,floor,",
            "floor,surface_slot,floor,head",
            "floor,surface_slot,wall,",
            "floor,character,floor,"
    })
    void 배경_악세사리_잘못된_배치정보는_카테고리_보상이_아니다(
            String category, String placement, String surface, String character) {
        assertThat(GachaCategory.fromItem(item(category, placement, surface, character))).isNull();
    }

    private Item item(String category, String placement, String surface, String character) {
        return new Item(new Theme("any_theme", "어떤 테마", null, true),
                category, placement, surface, character, "아이템", null, null,
                "items/any.png", false, true);
    }
}
