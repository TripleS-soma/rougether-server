package com.triples.rougether.batch.moderation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

// 피드 글·댓글 자동 유해 판정(#478) 설정. apiKey 는 TYPESAFE_API_KEY(SSM→batch.env) 주입, 커밋 금지.
// 키가 비면 판정을 하지 않음(외부 호출 0회). 모델은 버전을 고정해 판정 기준이 모르게 바뀌지 않게 함.
// flagThreshold: 유해 확률(1 − P(NONE))이 이 값 이상이면 자동 신고. 재배포 없이 env 로 조정함.
@ConfigurationProperties("rougether.moderation.screening")
public record ContentScreeningProperties(
        @DefaultValue("") String apiKey,
        @DefaultValue("https://api.typesafe.ai") String baseUrl,
        @DefaultValue("jev-1.14.0") String model,
        @DefaultValue("0.6") double flagThreshold,
        @DefaultValue("50") int batchSize,
        @DefaultValue("7") int windowDays,
        @DefaultValue("5s") Duration timeout,
        @DefaultValue("2") int maxRetries,
        @DefaultValue("1s") Duration retryBackoff) {

    public ContentScreeningProperties {
        if (!(flagThreshold > 0 && flagThreshold <= 1)) {
            throw new IllegalArgumentException("flag-threshold 는 0 초과 1 이하여야 함");
        }
        if (batchSize < 1 || batchSize > 200 || windowDays < 1 || maxRetries < 0) {
            throw new IllegalArgumentException("자동 유해 판정 batch 설정이 범위를 벗어남");
        }
    }

    public boolean enabled() {
        return StringUtils.hasText(apiKey);
    }

    public Duration window() {
        return Duration.ofDays(windowDays);
    }
}
