package com.triples.rougether.userapi.auth.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

// allowedClientIds는 identityToken의 aud 대조용 허용 목록(App ID/Service ID, 비밀 아님).
// 비면 AppleTokenVerifier가 전부 거부함(fail-closed).
@ConfigurationProperties("apple")
public record AppleProperties(List<String> allowedClientIds, String keysUrl) {
}
