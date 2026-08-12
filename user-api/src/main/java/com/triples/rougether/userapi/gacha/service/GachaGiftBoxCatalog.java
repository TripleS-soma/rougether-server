package com.triples.rougether.userapi.gacha.service;

import com.triples.rougether.domain.gacha.entity.Gacha;
import java.util.Map;

// 뽑기 화면에서 바로 사용할 투명 선물상자 에셋을 테마 코드에 연결함.
public final class GachaGiftBoxCatalog {

    private static final String DEFAULT_ASSET_KEY =
            "items/0c213078-69ce-4a77-a729-9144905dfc22.png";

    private static final Map<String, String> ASSET_KEY_BY_THEME_CODE = Map.ofEntries(
            Map.entry("forest-sage", "items/0c213078-69ce-4a77-a729-9144905dfc22.png"),
            Map.entry("calm-hanok", "items/643162b1-276e-4c93-98cb-9a5c706f677f.png"),
            Map.entry("bakery-morning", "items/7ae25d25-e15b-413b-ba97-650a1645514f.png"),
            Map.entry("cozy-space", "items/0ee18a21-a533-4342-aee7-fae4828897d6.png"),
            Map.entry("rainy-afternoon-study-imagegen-v2",
                    "items/a13c2174-53ec-4ee0-bcd9-3bdc86863965.png"),
            Map.entry("onsen-bath-routine-imagegen-v2",
                    "items/2b4e84ed-7c21-4efa-9dbd-c5095d5c87f6.png"),
            Map.entry("cloud-nap-room-imagegen-v2",
                    "items/6444611a-fd13-48ab-b1a0-4d7d979ac920.png"),
            Map.entry("stationery-study-room-imagegen-v2",
                    "items/7cd56955-5318-40ba-98a3-1b048c8dbd09.png"),
            Map.entry("cozy-developer-room", "items/8c3933ba-f466-4362-b77b-fa82d107e26f.png"),
            Map.entry("pastel-cyberpunk-room", "items/5da707d5-0865-4533-b724-a060d1a73e29.png"),
            Map.entry("cozy-zombie-hideout", "items/952e7591-e00a-48c1-b187-187eabcad07d.png"),
            Map.entry("summer-beach-room-v2", "items/441fbbfb-c09b-4b74-b4ef-c36e0616fea5.png"));

    private GachaGiftBoxCatalog() {
    }

    public static String assetKeyFor(Gacha gacha) {
        if (gacha.getTheme() == null) {
            return DEFAULT_ASSET_KEY;
        }
        return ASSET_KEY_BY_THEME_CODE.getOrDefault(gacha.getTheme().getCode(), DEFAULT_ASSET_KEY);
    }
}
