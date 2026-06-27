package com.triples.rougether.userapi.auth.service;

import com.triples.rougether.userapi.auth.config.JwtProperties;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.MemberRole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class TokenServiceTest {

    private static final String SECRET = "token-service-test-secret-key-32bytes-minimum-hs256!!";
    private final TokenService tokenService =
            new TokenService(new JwtProperties(SECRET, Duration.ofMinutes(30), Duration.ofDays(14)));

    @Test
    void access_토큰_발급_후_파싱하면_userId_와_role_을_돌려준다() {
        String token = tokenService.issueAccessToken(42L, MemberRole.NORMAL);

        AuthUser parsed = tokenService.parseAccessToken(token);
        assertThat(parsed.id()).isEqualTo(42L);
        assertThat(parsed.role()).isEqualTo(MemberRole.NORMAL);
    }

    @Test
    void 만료된_access_토큰은_INVALID_TOKEN_으로_거부한다() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant past = Instant.now().minus(Duration.ofHours(1));
        String expired = Jwts.builder()
                .subject("42")
                .claim("typ", "access")
                .issuedAt(Date.from(past.minus(Duration.ofMinutes(30))))
                .expiration(Date.from(past))
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> tokenService.parseAccessToken(expired))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.INVALID_TOKEN);
    }

    @Test
    void 다른_키로_서명된_토큰은_INVALID_TOKEN_으로_거부한다() {
        SecretKey otherKey =
                Keys.hmacShaKeyFor("a-totally-different-secret-key-32bytes-minimum-forge".getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String forged = Jwts.builder()
                .subject("42")
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(30))))
                .signWith(otherKey)
                .compact();

        assertThatThrownBy(() -> tokenService.parseAccessToken(forged))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.INVALID_TOKEN);
    }

    @Test
    void typ이_access가_아닌_JWT는_INVALID_TOKEN_으로_거부한다() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String refreshTyped = Jwts.builder()
                .subject("42")
                .claim("typ", "refresh")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(30))))
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> tokenService.parseAccessToken(refreshTyped))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.INVALID_TOKEN);
    }

    @Test
    void refresh_해시는_같은_입력에_대해_일관되고_생성된_토큰의_해시와_일치한다() {
        GeneratedRefreshToken generated = tokenService.generateRefreshToken();

        assertThat(generated.tokenHash()).isEqualTo(tokenService.hashRefreshToken(generated.raw()));
        assertThat(tokenService.hashRefreshToken("same")).isEqualTo(tokenService.hashRefreshToken("same"));
        assertThat(generated.raw()).isNotBlank();
        assertThat(generated.expiresAt()).isAfter(Instant.now());
    }
}
