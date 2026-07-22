package com.triples.rougether.adminapi.asset;

import java.util.regex.Pattern;

/**
 * 어드민 응답에서 사용하는 asset key 이름 규칙.
 * 가구 움짤은 {@code *-animated-v{N}.webp}, 캐릭터 움짤은 정해진 animations 경로로 구분한다.
 */
public final class AssetKeyClassifier {

    private static final Pattern FURNITURE_ANIMATED_WEBP =
            Pattern.compile("(?:^|/)[^/]+-animated-v\\d+\\.webp$");
    private static final Pattern CHARACTER_ANIMATED_WEBP =
            Pattern.compile("^characters/[^/]+/animations/(?:idle|pose-cycle|wave)\\.webp$");

    private AssetKeyClassifier() {
    }

    public static boolean isAnimated(String assetKey) {
        return assetKey != null
                && (FURNITURE_ANIMATED_WEBP.matcher(assetKey).find()
                || CHARACTER_ANIMATED_WEBP.matcher(assetKey).matches());
    }
}
