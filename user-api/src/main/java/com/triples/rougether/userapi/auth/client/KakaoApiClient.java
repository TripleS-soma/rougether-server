package com.triples.rougether.userapi.auth.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.config.KakaoProperties;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// 카카오 REST API 호출. access token 검증(app_id 대조) + 사용자 정보(회원번호·email) 조회.
@Component
public class KakaoApiClient {

    private final RestClient restClient;
    private final Long appId;

    @Autowired
    public KakaoApiClient(KakaoProperties properties) {
        this(RestClient.builder().baseUrl(properties.apiBaseUrl()).build(), properties.appId());
    }

    // 테스트에서 MockRestServiceServer로 바인딩한 RestClient를 주입하기 위한 생성자.
    KakaoApiClient(RestClient restClient, Long appId) {
        this.restClient = restClient;
        this.appId = appId;
    }

    public KakaoUser fetchUser(String accessToken) {
        TokenInfo tokenInfo = get("/v1/user/access_token_info", accessToken, TokenInfo.class);
        // 다른 카카오 앱에서 발급된 토큰 치환 차단: app_id가 우리 앱과 일치해야 함.
        // fail-closed: appId 설정이 없으면(null) 검증을 건너뛰지 않고 전부 거부함(방어가 조용히 꺼지지 않도록).
        if (!Objects.equals(appId, tokenInfo.appId())) {
            throw new BusinessException(AuthErrorCode.OAUTH_KAKAO_TOKEN_INVALID);
        }
        UserMe me = get("/v2/user/me", accessToken, UserMe.class);
        String email = me.kakaoAccount() == null ? null : me.kakaoAccount().email();
        return new KakaoUser(String.valueOf(me.id()), email);
    }

    private <T> T get(String uri, String accessToken, Class<T> type) {
        try {
            return restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        if (response.getStatusCode().value() == 401) {
                            throw new BusinessException(AuthErrorCode.OAUTH_KAKAO_TOKEN_INVALID);
                        }
                        throw new BusinessException(AuthErrorCode.OAUTH_KAKAO_UNAVAILABLE);
                    })
                    .body(type);
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            // 타임아웃·연결 실패 등 네트워크 오류
            throw new BusinessException(AuthErrorCode.OAUTH_KAKAO_UNAVAILABLE);
        }
    }

    private record TokenInfo(@JsonProperty("app_id") Long appId) {
    }

    private record UserMe(Long id, @JsonProperty("kakao_account") KakaoAccount kakaoAccount) {
        private record KakaoAccount(String email) {
        }
    }
}
