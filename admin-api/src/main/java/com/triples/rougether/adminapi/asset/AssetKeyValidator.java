package com.triples.rougether.adminapi.asset;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

// 관리자 입력 object key를 S3·DB·CDN에서 동일하게 사용할 수 있는 형태로 제한함.
public final class AssetKeyValidator {

    private static final int MAX_KEY_LENGTH = 255;
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*");
    private static final Map<String, Set<String>> EXTENSIONS_BY_CONTENT_TYPE = Map.of(
            "image/png", Set.of("png"),
            "image/jpeg", Set.of("jpg", "jpeg"),
            "image/webp", Set.of("webp"));

    private AssetKeyValidator() {
    }

    public static String normalize(String requestedKey, String kind, String contentType) {
        if (requestedKey == null || requestedKey.isBlank()) {
            return null;
        }

        String key = requestedKey.trim();
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("asset key는 255자 이하여야 합니다.");
        }
        if (!key.startsWith(kind + "/")) {
            throw new IllegalArgumentException("asset key는 선택한 kind/로 시작해야 합니다.");
        }
        if (!SAFE_KEY.matcher(key).matches()
                || key.contains("//")
                || key.endsWith("/")
                || hasTraversalSegment(key)) {
            throw new IllegalArgumentException("asset key에는 영문, 숫자, -, _, ., /만 사용할 수 있습니다.");
        }

        String extension = extensionOf(key);
        Set<String> allowedExtensions = EXTENSIONS_BY_CONTENT_TYPE.get(contentType);
        if (allowedExtensions == null || !allowedExtensions.contains(extension)) {
            throw new IllegalArgumentException("asset key 확장자와 이미지 형식이 일치해야 합니다.");
        }
        return key;
    }

    private static boolean hasTraversalSegment(String key) {
        for (String segment : key.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static String extensionOf(String key) {
        int lastSlash = key.lastIndexOf('/');
        int lastDot = key.lastIndexOf('.');
        if (lastDot <= lastSlash || lastDot == key.length() - 1) {
            return "";
        }
        return key.substring(lastDot + 1).toLowerCase(Locale.ROOT);
    }
}
