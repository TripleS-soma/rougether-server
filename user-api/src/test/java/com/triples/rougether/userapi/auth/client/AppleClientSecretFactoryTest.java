package com.triples.rougether.userapi.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.config.AppleProperties;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppleClientSecretFactoryTest {

    private static KeyPair generateP256KeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String toPem(KeyPair keyPair) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    private static AppleProperties properties(String privateKeyPem) {
        return new AppleProperties(List.of("com.triples.rougether"),
                "https://appleid.apple.com/auth/keys", "TEAM123", "KEY456", privateKeyPem, "enc-key");
    }

    @Test
    void ES256_으로_서명된_client_secret_JWT_를_생성한다() throws Exception {
        KeyPair keyPair = generateP256KeyPair();
        AppleClientSecretFactory factory = new AppleClientSecretFactory(properties(toPem(keyPair)));

        SignedJWT jwt = SignedJWT.parse(factory.create());

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("KEY456");
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("TEAM123");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("com.triples.rougether");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("https://appleid.apple.com");
        JWSVerifier verifier = new ECDSAVerifier((ECPublicKey) keyPair.getPublic());
        assertThat(jwt.verify(verifier)).isTrue();
    }

    @Test
    void 환경변수_주입으로_개행이_이스케이프된_키도_파싱한다() throws Exception {
        KeyPair keyPair = generateP256KeyPair();
        String escaped = toPem(keyPair).replace("\n", "\\n");
        AppleClientSecretFactory factory = new AppleClientSecretFactory(properties(escaped));

        SignedJWT jwt = SignedJWT.parse(factory.create());

        assertThat(jwt.verify(new ECDSAVerifier((ECPublicKey) keyPair.getPublic()))).isTrue();
    }

    @Test
    void 시크릿_미설정이면_UNAVAILABLE_로_실패한다() {
        // fail-closed: team_id/key_id/private key 없는 환경에서 조용히 지나가지 않아야 함.
        AppleClientSecretFactory factory = new AppleClientSecretFactory(new AppleProperties(
                List.of("com.triples.rougether"), "https://appleid.apple.com/auth/keys",
                "", "", "", ""));

        assertThatThrownBy(factory::create)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
    }

    @Test
    void 잘못된_키_형식이면_UNAVAILABLE_로_실패한다() {
        AppleClientSecretFactory factory = new AppleClientSecretFactory(properties("not-a-key"));

        assertThatThrownBy(factory::create)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
    }
}
