package com.triples.rougether.domain.gacha.entity;

import java.util.Set;

/**
 * 현재 뽑기에서 사용하는 등급 값.
 *
 * <p>DB 컬럼은 기존 문자열 계약을 유지하고, 값의 정의와 검증만 한 곳에서 관리한다.</p>
 */
public final class GachaRarity {

    public static final String NORMAL = "일반";
    public static final String RARE = "희귀";
    public static final String LEGENDARY = "전설";

    private static final Set<String> SUPPORTED_VALUES = Set.of(NORMAL, RARE, LEGENDARY);

    private GachaRarity() {
    }

    public static boolean isSupported(String rarity) {
        return rarity != null && SUPPORTED_VALUES.contains(rarity);
    }
}
