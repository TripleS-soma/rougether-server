package com.triples.rougether.userapi.auth.service;

import com.triples.rougether.userapi.auth.config.JwtProperties;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.MemberRole;

import com.triples.rougether.common.error.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import javax.crypto.SecretKey;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

// 자체 토큰 발급/검증. access 는 HS256 JWT(stateless), refresh 는 불투명 랜덤 + SHA-256 해시.
@Component
public class TokenService {

    private static final String TYPE_CLAIM = "typ";
    private static final String ACCESS_TYPE = "access";
    private static final String ROLE_CLAIM = "role";
    private static final int REFRESH_BYTES = 32;

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenService(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTtl = properties.accessTtl();
        this.refreshTtl = properties.refreshTtl();
    }

    public String issueAccessToken(Long userId, MemberRole role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(TYPE_CLAIM, ACCESS_TYPE)
                .claim(ROLE_CLAIM, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    // access 토큰에서 인증 주체(AuthUser) 복원. 서명·만료·typ 위반은 모두 INVALID_TOKEN 으로 통일함.
    public AuthUser parseAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!ACCESS_TYPE.equals(claims.get(TYPE_CLAIM, String.class))) {
                throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
            }
            Long userId = Long.valueOf(claims.getSubject());
            MemberRole role = parseRole(claims.get(ROLE_CLAIM, String.class));
            return new AuthUser(userId, role);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        }
    }

    // role 클레임이 없거나 알 수 없으면 NORMAL 로 처리함(구토큰·확장 대비).
    private MemberRole parseRole(String role) {
        if (role == null) {
            return MemberRole.NORMAL;
        }
        try {
            return MemberRole.valueOf(role);
        } catch (IllegalArgumentException e) {
            return MemberRole.NORMAL;
        }
    }

    public GeneratedRefreshToken generateRefreshToken() {
        byte[] bytes = new byte[REFRESH_BYTES];
        secureRandom.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new GeneratedRefreshToken(raw, hashRefreshToken(raw), Instant.now().plus(refreshTtl));
    }

    public String hashRefreshToken(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
