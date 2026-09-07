package com.triples.rougether.userapi.billing.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.billing.config.BillingProperties;
import com.triples.rougether.userapi.billing.error.BillingErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.stereotype.Component;

@Component
public class PurchaseReferenceCipher {
    private final BillingProperties config;
    private final SecureRandom random = new SecureRandom();
    public PurchaseReferenceCipher(BillingProperties config) { this.config = config; }
    public String encrypt(String reference) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv);
            byte[] encrypted = cipher.doFinal(reference.getBytes(StandardCharsets.UTF_8));
            byte[] bytes = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, bytes, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (GeneralSecurityException e) { throw unavailable(); }
    }
    public String decrypt(String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length < 28) throw unavailable();
            return new String(cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(bytes, 12))
                    .doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) { throw unavailable(); }
    }
    private Cipher cipher(int mode, byte[] iv) throws GeneralSecurityException {
        // 결제 토큰 전용 32바이트 키를 사용함. OAuth 키나 개발용 기본값을 재사용하지 않음.
        byte[] key;
        try { key = Base64.getDecoder().decode(config.encryptionKey()); }
        catch (IllegalArgumentException e) { throw unavailable(); }
        if (key.length != 32) throw unavailable();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return cipher;
    }
    public static String hash(String reference) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(reference.getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    private BusinessException unavailable() { return new BusinessException(BillingErrorCode.BILLING_UNAVAILABLE); }
}
