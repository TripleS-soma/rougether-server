package com.triples.rougether.userapi.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.RemoteKeySourceException;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

// 인메모리 RSA 키페어로 idToken을 서명한 뒤 검증 통과/실패 케이스를 확인함.
// 서명·exp는 NimbusJwtDecoder(withPublicKey)가, iss/aud는 GoogleTokenVerifier가 검증함.
class GoogleTokenVerifierTest {

    private static final String ISS = "https://accounts.google.com";
    private static final String CLIENT_ID = "client-a.apps.googleusercontent.com";

    private static KeyPair keyPair;
    private static KeyPair otherKeyPair;

    private final GoogleTokenVerifier verifier =
            new GoogleTokenVerifier(decoderFor((RSAPublicKey) keyPair.getPublic()), List.of(CLIENT_ID));

    @BeforeAll
    static void generateKeys() throws NoSuchAlgorithmException {
        keyPair = rsaKeyPair();
        otherKeyPair = rsaKeyPair();
    }

    @Test
    void 정상_토큰이면_sub와_email을_반환한다() {
        String token = token(builder -> builder
                .issuer(ISS).audience().add(CLIENT_ID).and()
                .subject("google-sub-1").claim("email", "a@b.com"),
                keyPair);

        GoogleUser user = verifier.verify(token);

        assertThat(user.id()).isEqualTo("google-sub-1");
        assertThat(user.email()).isEqualTo("a@b.com");
    }

    @Test
    void 이메일_클레임이_없으면_email_은_null_이다() {
        String token = token(builder -> builder
                .issuer(ISS).audience().add(CLIENT_ID).and()
                .subject("google-sub-2"),
                keyPair);

        GoogleUser user = verifier.verify(token);

        assertThat(user.id()).isEqualTo("google-sub-2");
        assertThat(user.email()).isNull();
    }

    @Test
    void 서명이_다른_키면_토큰_무효로_거부한다() {
        // 우리 공개키와 짝이 아닌 키로 서명 → 서명 검증 실패.
        String token = token(builder -> builder
                .issuer(ISS).audience().add(CLIENT_ID).and().subject("x"),
                otherKeyPair);

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_GOOGLE_TOKEN_INVALID);
    }

    @Test
    void 만료된_토큰이면_거부한다() {
        // 기본 clock skew(60s)를 넘겨 확실히 만료시킴.
        Instant expired = Instant.now().minusSeconds(3600);
        String token = Jwts.builder()
                .issuer(ISS).audience().add(CLIENT_ID).and().subject("x")
                .issuedAt(Date.from(expired.minusSeconds(60)))
                .expiration(Date.from(expired))
                .signWith(privateKey(keyPair), Jwts.SIG.RS256)
                .compact();

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_GOOGLE_TOKEN_INVALID);
    }

    @Test
    void aud가_허용목록에_없으면_거부한다() {
        // 다른 구글 앱에서 발급된 토큰 치환 차단.
        String token = token(builder -> builder
                .issuer(ISS).audience().add("someone-else.apps.googleusercontent.com").and().subject("x"),
                keyPair);

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_GOOGLE_TOKEN_INVALID);
    }

    @Test
    void iss가_구글이_아니면_거부한다() {
        String token = token(builder -> builder
                .issuer("https://evil.example.com").audience().add(CLIENT_ID).and().subject("x"),
                keyPair);

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_GOOGLE_TOKEN_INVALID);
    }

    @Test
    void 허용목록이_비면_모든_aud를_거부한다_failClosed() {
        GoogleTokenVerifier emptyAllowlist =
                new GoogleTokenVerifier(decoderFor((RSAPublicKey) keyPair.getPublic()), List.of());
        String token = token(builder -> builder
                .issuer(ISS).audience().add(CLIENT_ID).and().subject("x"),
                keyPair);

        assertThatThrownBy(() -> emptyAllowlist.verify(token))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_GOOGLE_TOKEN_INVALID);
    }

    @Test
    void JWK_조회_실패면_UNAVAILABLE로_분류한다() {
        // Nimbus는 JWK 조회 실패를 RemoteKeySourceException을 감싼 JwtException으로 던짐 → 502.
        JwtDecoder failing = token -> {
            throw new JwtException("jwk fetch failed", new RemoteKeySourceException("keys endpoint down", null));
        };
        GoogleTokenVerifier verifier = new GoogleTokenVerifier(failing, List.of(CLIENT_ID));

        assertThatThrownBy(() -> verifier.verify("any"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_GOOGLE_UNAVAILABLE);
    }

    @Test
    void 원격키_오류가_아닌_JwtException은_토큰_무효로_분류한다() {
        // 서명 위조 등은 원인 체인에 RemoteKeySourceException이 없음 → 502로 새지 않고 401.
        JwtDecoder failing = token -> {
            throw new JwtException("An error occurred while attempting to decode the Jwt: Invalid signature");
        };
        GoogleTokenVerifier verifier = new GoogleTokenVerifier(failing, List.of(CLIENT_ID));

        assertThatThrownBy(() -> verifier.verify("any"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_GOOGLE_TOKEN_INVALID);
    }

    private static JwtDecoder decoderFor(RSAPublicKey publicKey) {
        // JWK 조회 없이 주어진 공개키로 서명·exp를 검증하는 디코더.
        return NimbusJwtDecoder.withPublicKey(publicKey).build();
    }

    private static String token(java.util.function.UnaryOperator<io.jsonwebtoken.JwtBuilder> claims, KeyPair signer) {
        Instant now = Instant.now();
        return claims.apply(Jwts.builder())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith(privateKey(signer), Jwts.SIG.RS256)
                .compact();
    }

    private static RSAPrivateKey privateKey(KeyPair keyPair) {
        return (RSAPrivateKey) keyPair.getPrivate();
    }

    private static KeyPair rsaKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
