package com.triples.rougether.userapi.gacha;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.gacha.entity.Gacha;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.userapi.gacha.service.GachaGiftBoxCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GachaGiftBoxCatalogTest {

    @ParameterizedTest
    @CsvSource({
            "forest_sage, items/0c213078-69ce-4a77-a729-9144905dfc22.png",
            "calm_hanok, items/643162b1-276e-4c93-98cb-9a5c706f677f.png",
            "bakery_morning, items/7ae25d25-e15b-413b-ba97-650a1645514f.png",
            "cozy_space, items/0ee18a21-a533-4342-aee7-fae4828897d6.png",
            "rainy_afternoon_study, items/a13c2174-53ec-4ee0-bcd9-3bdc86863965.png",
            "onsen_bath_routine, items/2b4e84ed-7c21-4efa-9dbd-c5095d5c87f6.png",
            "cloud_nap_room, items/6444611a-fd13-48ab-b1a0-4d7d979ac920.png",
            "stationery_study_room, items/7cd56955-5318-40ba-98a3-1b048c8dbd09.png",
            "cozy_developer_room, items/8c3933ba-f466-4362-b77b-fa82d107e26f.png",
            "pastel_cyberpunk_room, items/5da707d5-0865-4533-b724-a060d1a73e29.png",
            "cozy_zombie_hideout, items/952e7591-e00a-48c1-b187-187eabcad07d.png",
            "summer_beach_room, items/441fbbfb-c09b-4b74-b4ef-c36e0616fea5.png"
    })
    void 운영_테마는_DB_코드에_매핑된_선물상자_key를_내려준다(String themeCode, String expectedAssetKey) {
        Theme theme = new Theme(themeCode, themeCode, null, true);
        Gacha gacha = new Gacha(themeCode, themeCode,
                CurrencyType.COIN, 25, 1, theme, true);

        assertThat(GachaGiftBoxCatalog.assetKeyFor(gacha))
                .isEqualTo(expectedAssetKey)
                .doesNotContain("698ebc78-8273-4bf7-85d4-a7ea81c7c4d0");
    }

    @Test
    void 테마가_없는_캐릭터_뽑기도_기본_선물상자를_내려준다() {
        Gacha gacha = new Gacha("characters", "캐릭터 뽑기",
                CurrencyType.COIN, 500, 1, null, true);

        assertThat(GachaGiftBoxCatalog.assetKeyFor(gacha))
                .isEqualTo("items/0c213078-69ce-4a77-a729-9144905dfc22.png");
    }

    @Test
    void 아직_매핑되지_않은_테마도_기본_선물상자를_내려준다() {
        Theme theme = new Theme("new-theme", "새 테마", null, true);
        Gacha gacha = new Gacha("new-theme", "새 테마 뽑기",
                CurrencyType.COIN, 25, 1, theme, true);

        assertThat(GachaGiftBoxCatalog.assetKeyFor(gacha))
                .isEqualTo("items/0c213078-69ce-4a77-a729-9144905dfc22.png");
    }
}
