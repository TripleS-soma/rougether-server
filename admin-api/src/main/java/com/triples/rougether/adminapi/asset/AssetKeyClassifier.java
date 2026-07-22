package com.triples.rougether.adminapi.asset;

import java.util.regex.Pattern;

/** 어드민 응답에서 사용하는 asset key 이름 규칙. 가구 움짤은 {@code *-animated-v{N}.webp}로 구분한다. */
public final class AssetKeyClassifier {

    private static final Pattern ANIMATED_WEBP =
            Pattern.compile("(?:^|/)[^/]+-animated-v\\d+\\.webp$");

    private AssetKeyClassifier() {
    }

    public static boolean isAnimated(String assetKey) {
        return assetKey != null && ANIMATED_WEBP.matcher(assetKey).find();
    }
}
