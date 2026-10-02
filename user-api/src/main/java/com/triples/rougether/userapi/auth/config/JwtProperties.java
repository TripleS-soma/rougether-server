package com.triples.rougether.userapi.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// refreshReuseGrace: 회전으로 폐기된 refresh 를 응답 유실 재시도로 보고 재발급해 주는 유예(미설정이면 60초, mobile #1388).
// 생성자는 정식 생성자 하나만 둠(record 바인딩이 생성자를 고르지 못하는 일을 피함).
@ConfigurationProperties("jwt")
public record JwtProperties(String secret, Duration accessTtl, Duration refreshTtl, Duration refreshReuseGrace) {

    public static final Duration DEFAULT_REFRESH_REUSE_GRACE = Duration.ofSeconds(60);

    public JwtProperties {
        if (refreshReuseGrace == null) {
            refreshReuseGrace = DEFAULT_REFRESH_REUSE_GRACE;
        }
    }
}
