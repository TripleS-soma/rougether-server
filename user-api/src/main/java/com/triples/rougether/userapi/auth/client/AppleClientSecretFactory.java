package com.triples.rougether.userapi.auth.client;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.config.AppleProperties;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.springframework.stereotype.Component;

// 애플 토큰 교환·revoke 공통 client_secret(ES256 JWT) 생성기.
// fail-closed: team_id/key_id/private key 시크릿이 하나라도 없으면 조용히 건너뛰지 않고 502로 실패함.
@Component
public class AppleClientSecretFactory {

    private static final String APPLE_AUDIENCE = "https://appleid.apple.com";
    // 애플 허용 최대 6개월. 요청 직전마다 생성하므로 짧게 씀.
    private static final long TTL_SECONDS = 300;

    private final String teamId;
    private final String keyId;
    private final String privateKeyPem;
    private final String clientId;

    public AppleClientSecretFactory(AppleProperties properties) {
        this.teamId = properties.teamId();
        this.keyId = properties.keyId();
        this.privateKeyPem = properties.privateKey();
        // 교환·revoke의 client_id는 코드를 발급받은 앱(App ID)이어야 함. 허용 목록의 첫 항목을 앱 번들 ID로 씀.
        List<String> allowed = properties.allowedClientIds();
        this.clientId = (allowed == null || allowed.isEmpty()) ? null : allowed.getFirst();
    }

    public String clientId() {
        if (isBlank(clientId)) {
            throw new BusinessException(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
        }
        return clientId;
    }

    public String create() {
        if (isBlank(teamId) || isBlank(keyId) || isBlank(privateKeyPem) || isBlank(clientId)) {
            throw new BusinessException(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
        }
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(teamId)
                    .subject(clientId)
                    .audience(APPLE_AUDIENCE)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(TTL_SECONDS)))
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(), claims);
            jwt.sign(new ECDSASigner(parsePrivateKey(privateKeyPem)));
            return jwt.serialize();
        } catch (JOSEException | java.security.GeneralSecurityException | IllegalArgumentException e) {
            // 키 형식 오류·서명 실패 = 서버 설정 문제. 클라이언트 입력 탓이 아니므로 502로 통일함.
            throw new BusinessException(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
        }
    }

    private ECPrivateKey parsePrivateKey(String pem) throws java.security.GeneralSecurityException {
        // 환경변수로 주입된 .p8은 개행이 리터럴 \n 로 들어올 수 있어 복원함.
        String normalized = pem.replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(normalized);
        return (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
