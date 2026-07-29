package com.triples.rougether.userapi.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.config.AppleProperties;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppleRefreshTokenCipherTest {

    private static AppleProperties properties(String encKey) {
        return new AppleProperties(List.of("com.triples.rougether"),
                "https://appleid.apple.com/auth/keys", "TEAM123", "KEY456", "pem", encKey);
    }

    @Test
    void 암호화한_값을_복호화하면_원문이_나온다() {
        AppleRefreshTokenCipher cipher = new AppleRefreshTokenCipher(properties("enc-key"));

        String encrypted = cipher.encrypt("apple-rt-original");

        assertThat(encrypted).isNotEqualTo("apple-rt-original");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("apple-rt-original");
    }

    @Test
    void 같은_평문도_IV_가_달라_암호문이_매번_다르다() {
        AppleRefreshTokenCipher cipher = new AppleRefreshTokenCipher(properties("enc-key"));

        assertThat(cipher.encrypt("rt")).isNotEqualTo(cipher.encrypt("rt"));
    }

    @Test
    void 암호화_키_미설정이면_UNAVAILABLE_로_실패한다() {
        // fail-closed: 커밋된 기본 키로 조용히 암호화하지 않아야 함.
        AppleRefreshTokenCipher cipher = new AppleRefreshTokenCipher(properties(" "));

        assertThatThrownBy(() -> cipher.encrypt("rt"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
        assertThatThrownBy(() -> cipher.decrypt("whatever"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
    }

    @Test
    void 다른_키로_암호화한_값은_복호화가_거부된다() {
        AppleRefreshTokenCipher cipher = new AppleRefreshTokenCipher(properties("enc-key"));
        AppleRefreshTokenCipher otherKey = new AppleRefreshTokenCipher(properties("other-key"));

        String encrypted = otherKey.encrypt("rt");

        assertThatThrownBy(() -> cipher.decrypt(encrypted))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
    }
}
