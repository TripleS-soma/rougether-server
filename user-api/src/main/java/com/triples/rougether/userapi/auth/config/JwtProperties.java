package com.triples.rougether.userapi.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// refreshReuseGrace: 회전으로 폐기된 refresh 를 응답 유실 재시도로 보고 재발급해 주는 유예(미설정이면 60초, mobile #1388).
// refreshRotateBefore: 남은 유효기간이 이보다 짧을 때만 refresh 를 회전함(미설정이면 7일, mobile #1388 후속).
//   그보다 넉넉하면 같은 refresh 를 돌려주고 access 만 새로 줌 — 응답을 못 받은 클라이언트가 옛 refresh 로 다시 와도
//   그 토큰이 살아 있어 강제 로그아웃이 생기지 않음. refreshTtl 이상으로 두면 매번 회전(예전 동작).
// 생성자는 정식 생성자 하나만 둠(record 바인딩이 생성자를 고르지 못하는 일을 피함).
@ConfigurationProperties("jwt")
public record JwtProperties(String secret, Duration accessTtl, Duration refreshTtl, Duration refreshReuseGrace,
                            Duration refreshRotateBefore) {

    public static final Duration DEFAULT_REFRESH_REUSE_GRACE = Duration.ofSeconds(60);
    public static final Duration DEFAULT_REFRESH_ROTATE_BEFORE = Duration.ofDays(7);

    public JwtProperties {
        if (refreshReuseGrace == null) {
            refreshReuseGrace = DEFAULT_REFRESH_REUSE_GRACE;
        }
        if (refreshRotateBefore == null) {
            refreshRotateBefore = DEFAULT_REFRESH_ROTATE_BEFORE;
        }
    }
}
