package com.triples.rougether.userapi.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kakao")
public record KakaoProperties(String apiBaseUrl, Long appId) {
}
