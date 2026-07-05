package com.triples.rougether.userapi.auth.client;

import com.nimbusds.jose.RemoteKeySourceException;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.config.GoogleProperties;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

// 구글 idToken 검증. JwtDecoder(NimbusJwtDecoder)가 JWK 서명·exp를 검증하고,
// iss/aud(허용 client_id) 비즈니스 규칙은 여기서 검증함(디코더를 갈아끼워 오프라인 단위테스트 가능).
@Component
public class GoogleTokenVerifier {

    // 구글이 발급하는 iss 두 형태를 모두 허용함.
    private static final Set<String> VALID_ISSUERS =
            Set.of("https://accounts.google.com", "accounts.google.com");

    private final JwtDecoder jwtDecoder;
    private final List<String> allowedClientIds;

    @Autowired
    public GoogleTokenVerifier(@Qualifier("googleJwtDecoder") JwtDecoder jwtDecoder, GoogleProperties properties) {
        this(jwtDecoder, properties.allowedClientIds());
    }

    // 테스트에서 in-memory 키 기반 디코더/허용목록을 주입하기 위한 생성자.
    GoogleTokenVerifier(JwtDecoder jwtDecoder, List<String> allowedClientIds) {
        this.jwtDecoder = jwtDecoder;
        // fail-closed: 허용목록이 없으면(null) 빈 목록으로 두어 모든 aud를 거부함.
        this.allowedClientIds = allowedClientIds == null ? List.of() : List.copyOf(allowedClientIds);
    }

    public GoogleUser verify(String idToken) {
        Jwt jwt;
        try {
            // 서명·exp 검증. 실패 시 JwtException 계열.
            jwt = jwtDecoder.decode(idToken);
        } catch (JwtException e) {
            // JWK 조회/네트워크 실패만 502로 구분하고, 서명·형식·만료 등은 401(토큰 무효)로 통일함.
            if (isRemoteKeyError(e)) {
                throw new BusinessException(AuthErrorCode.OAUTH_GOOGLE_UNAVAILABLE);
            }
            throw new BusinessException(AuthErrorCode.OAUTH_GOOGLE_TOKEN_INVALID);
        }

        if (!VALID_ISSUERS.contains(jwt.getClaimAsString(JwtClaimNames.ISS))) {
            throw new BusinessException(AuthErrorCode.OAUTH_GOOGLE_TOKEN_INVALID);
        }
        // 다른 구글 앱에서 발급된 토큰 치환 차단: aud가 우리 허용 client_id와 겹쳐야 함(빈 목록이면 전부 거부).
        List<String> audiences = jwt.getAudience() == null ? List.of() : jwt.getAudience();
        if (audiences.stream().noneMatch(allowedClientIds::contains)) {
            throw new BusinessException(AuthErrorCode.OAUTH_GOOGLE_TOKEN_INVALID);
        }

        return new GoogleUser(jwt.getSubject(), jwt.getClaimAsString("email"));
    }

    // JWK 조회 실패(원격 키 소스 오류)가 원인 체인에 있으면 인증서버 장애로 판정함.
    private boolean isRemoteKeyError(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof RemoteKeySourceException) {
                return true;
            }
        }
        return false;
    }
}
