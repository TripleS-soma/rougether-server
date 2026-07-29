package com.triples.rougether.userapi.auth.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.config.AppleProperties;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

// oauth_accounts.apple_refresh_token_encrypted 저장용 AES-256-GCM 암복호화.
// fail-closed: 암호화 키 미설정 환경에서는 커밋된 기본 키로 조용히 암호화하지 않고 502로 실패함.
@Component
public class AppleRefreshTokenCipher {

    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final String encKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public AppleRefreshTokenCipher(AppleProperties properties) {
        this.encKey = properties.refreshTokenEncKey();
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new BusinessException(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] in = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(TAG_LENGTH_BITS, in, 0, IV_LENGTH_BYTES));
            byte[] plain = cipher.doFinal(in, IV_LENGTH_BYTES, in.length - IV_LENGTH_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new BusinessException(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
        }
    }

    private SecretKeySpec key() throws GeneralSecurityException {
        if (encKey == null || encKey.isBlank()) {
            throw new BusinessException(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
        }
        // 임의 길이 시크릿을 SHA-256으로 32바이트 AES 키로 정규화함.
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(encKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
}
