package com.triples.rougether.userapi.furniture.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("furniture.generation")
public record FurnitureGenerationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("gpt-6-astra") String model,
        @DefaultValue("gpt-image-2") String imageModel,
        @DefaultValue("180s") Duration timeout,
        @DefaultValue("3") int maxImageAttempts,
        @DefaultValue("6") int maxReviewAttempts,
        @DefaultValue("24h") Duration retention,
        @DefaultValue("items/cozy-developer-room/furniture/cozy-developer-room-cozy-chair.png")
        List<String> styleReferenceKeys) {

    public FurnitureGenerationProperties {
        if (maxImageAttempts < 1 || maxImageAttempts > 5 || maxReviewAttempts < 1 || maxReviewAttempts > 10
                || timeout.compareTo(Duration.ofSeconds(10)) < 0
                || timeout.compareTo(Duration.ofMinutes(5)) > 0
                || retention.compareTo(Duration.ofHours(1)) < 0 || retention.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("가구 생성 한도 설정이 허용 범위를 벗어남");
        }
        styleReferenceKeys = styleReferenceKeys == null ? List.of() : List.copyOf(styleReferenceKeys);
        if (styleReferenceKeys.isEmpty() || styleReferenceKeys.size() > 3
                || styleReferenceKeys.stream().anyMatch(k -> !k.startsWith("items/")
                    || k.contains("..") || k.contains(":") || k.contains("?") || k.contains("\\"))) {
            throw new IllegalArgumentException("가구 스타일 기준은 items/ 아래 1~3개 에셋이어야 함");
        }
    }
}
