package com.triples.rougether.userapi.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, KakaoProperties.class, GoogleProperties.class})
public class AuthConfig {

    // 구글 JWK로 RS256 서명·exp를 검증하는 디코더. JWK 조회는 최초 decode 시점에 지연 수행됨(빈 생성 시 네트워크 X).
    // iss/aud 비즈니스 규칙은 GoogleTokenVerifier가 담당함.
    @Bean
    JwtDecoder googleJwtDecoder(GoogleProperties properties) {
        return NimbusJwtDecoder.withJwkSetUri(properties.certsUrl()).build();
    }
}
