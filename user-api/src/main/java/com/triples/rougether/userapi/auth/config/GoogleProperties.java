package com.triples.rougether.userapi.auth.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

// allowedClientIds는 idToken의 aud 대조용 허용 목록(비밀 아님). 비면 GoogleTokenVerifier가 전부 거부함(fail-closed).
@ConfigurationProperties("google")
public record GoogleProperties(List<String> allowedClientIds, String certsUrl) {
}
