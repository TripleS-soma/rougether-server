package com.triples.rougether.userapi.auth.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

// allowedClientIds는 identityToken의 aud 대조용 허용 목록(App ID/Service ID, 비밀 아님).
// 비면 AppleTokenVerifier가 전부 거부함(fail-closed).
// teamId/keyId/privateKey는 토큰 교환·revoke의 client_secret(ES256 JWT) 서명용 시크릿(환경변수 주입, 커밋 금지).
// refreshTokenEncKey는 apple refresh token 저장 암호화 키. 시크릿 미설정 시 관련 기능이 502로 실패함(fail-closed).
@ConfigurationProperties("apple")
public record AppleProperties(List<String> allowedClientIds, String keysUrl,
                              String teamId, String keyId, String privateKey,
                              String refreshTokenEncKey) {
}
