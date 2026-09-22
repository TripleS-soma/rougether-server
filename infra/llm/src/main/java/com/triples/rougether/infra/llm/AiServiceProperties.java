package com.triples.rougether.infra.llm;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// 분리한 AI 서비스 연결 설정. 공급자 API key와 별도의 내부 인증 토큰을 사용함.
@ConfigurationProperties("ai.service")
public record AiServiceProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String token,
        @DefaultValue("95s") Duration timeout,
        @DefaultValue("false") boolean allowInsecureHttp) {

    public AiServiceProperties {
        if (enabled) {
            if (token == null || token.length() < 32 || token.chars().anyMatch(c -> c <= 32 || c > 126)) {
                throw new IllegalArgumentException("AI service token은 공백 없는 ASCII 32자 이상이어야 함");
            }
            URI uri;
            try {
                uri = URI.create(baseUrl == null ? "" : baseUrl);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("AI service URL 형식이 올바르지 않음");
            }
            boolean local = Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
            boolean safeScheme = "https".equals(uri.getScheme())
                    || ("http".equals(uri.getScheme()) && (local || allowInsecureHttp));
            if (!safeScheme || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw new IllegalArgumentException("AI service URL은 HTTPS 또는 명시적으로 허용한 내부 HTTP origin이어야 함");
            }
            if (timeout == null || timeout.compareTo(Duration.ofSeconds(90)) <= 0
                    || timeout.compareTo(Duration.ofSeconds(120)) > 0) {
                throw new IllegalArgumentException("AI service timeout은 서버의 90초 제한보다 길고 120초 이하여야 함");
            }
        }
    }

    @Override
    public String toString() {
        return "AiServiceProperties[enabled=" + enabled + ", token=REDACTED]";
    }
}
