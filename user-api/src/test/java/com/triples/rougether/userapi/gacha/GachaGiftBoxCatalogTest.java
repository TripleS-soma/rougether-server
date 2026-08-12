package com.triples.rougether.userapi.gacha;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.gacha.entity.Gacha;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.userapi.gacha.service.GachaGiftBoxCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GachaGiftBoxCatalogTest {

    @Test
    void 한옥_테마는_투명_선물상자_key를_내려준다() {
        Theme theme = new Theme("calm-hanok", "고즈넉 한옥", null, true);
        Gacha gacha = new Gacha("calm-hanok", "고즈넉 한옥 뽑기",
                CurrencyType.COIN, 25, 1, theme, true);

        assertThat(GachaGiftBoxCatalog.assetKeyFor(gacha))
                .isEqualTo("items/643162b1-276e-4c93-98cb-9a5c706f677f.png");
    }

    @Test
    void 베이커리_테마는_핑크_배경본이_아닌_투명본을_내려준다() {
        Theme theme = new Theme("bakery-morning", "작은 베이커리 아침", null, true);
        Gacha gacha = new Gacha("bakery-morning", "작은 베이커리 아침 뽑기",
                CurrencyType.COIN, 25, 1, theme, true);

        assertThat(GachaGiftBoxCatalog.assetKeyFor(gacha))
                .isEqualTo("items/7ae25d25-e15b-413b-ba97-650a1645514f.png")
                .doesNotContain("698ebc78-8273-4bf7-85d4-a7ea81c7c4d0");
    }

    @Test
    void 테마가_없는_캐릭터_뽑기도_기본_선물상자를_내려준다() {
        Gacha gacha = new Gacha("characters", "캐릭터 뽑기",
                CurrencyType.COIN, 500, 1, null, true);

        assertThat(GachaGiftBoxCatalog.assetKeyFor(gacha))
                .isEqualTo("items/0c213078-69ce-4a77-a729-9144905dfc22.png");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "forest-sage",
            "calm-hanok",
            "bakery-morning",
            "cozy-space",
            "rainy-afternoon-study-imagegen-v2",
            "onsen-bath-routine-imagegen-v2",
            "cloud-nap-room-imagegen-v2",
            "stationery-study-room-imagegen-v2",
            "cozy-developer-room",
            "pastel-cyberpunk-room",
            "cozy-zombie-hideout",
            "summer-beach-room-v2"
    })
    void 운영_테마는_핑크_배경본을_노출하지_않는다(String themeCode) {
        Theme theme = new Theme(themeCode, themeCode, null, true);
        Gacha gacha = new Gacha(themeCode, themeCode,
                CurrencyType.COIN, 25, 1, theme, true);

        assertThat(GachaGiftBoxCatalog.assetKeyFor(gacha))
                .startsWith("items/")
                .endsWith(".png")
                .doesNotContain("698ebc78-8273-4bf7-85d4-a7ea81c7c4d0");
    }
}
