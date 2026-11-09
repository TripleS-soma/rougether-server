package com.triples.rougether.domain.shop.policy;

import com.triples.rougether.domain.shop.entity.Item;

public final class EventItemEligibility {

    public static final String CHRISTMAS_THEME_CODE = "snowy_christmas_202611";

    private EventItemEligibility() {
    }

    public static boolean isChristmas(Item item) {
        return item.getTheme() != null && CHRISTMAS_THEME_CODE.equals(item.getTheme().getCode());
    }
}
