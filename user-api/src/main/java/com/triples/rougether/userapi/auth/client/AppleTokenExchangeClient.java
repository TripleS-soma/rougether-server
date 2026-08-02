package com.triples.rougether.userapi.auth.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// 애플 authorizationCode → refresh token 교환. 회원탈퇴 시 연동 해제(revoke)에 쓸 토큰을 확보함.
@Component
public class AppleTokenExchangeClient {

    private static final String APPLE_BASE_URL = "https://appleid.apple.com";

    private final RestClient restClient;
    private final AppleClientSecretFactory clientSecretFactory;

    @Autowired
    public AppleTokenExchangeClient(AppleClientSecretFactory clientSecretFactory) {
        this(RestClient.builder().baseUrl(APPLE_BASE_URL).build(), clientSecretFactory);
    }

    // 테스트에서 MockRestServiceServer로 바인딩한 RestClient를 주입하기 위한 생성자.
    AppleTokenExchangeClient(RestClient restClient, AppleClientSecretFactory clientSecretFactory) {
        this.restClient = restClient;
        this.clientSecretFactory = clientSecretFactory;
    }

    public String exchangeRefreshToken(String authorizationCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientSecretFactory.clientId());
        form.add("client_secret", clientSecretFactory.create());
        form.add("grant_type", "authorization_code");
        form.add("code", authorizationCode);

        TokenResponse response;
        try {
            response = restClient.post()
                    .uri("/auth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError(), (request, res) -> {
                        // invalid_grant 등 코드 문제(만료·재사용 포함)는 클라이언트 입력 무효로 취급함.
                        throw new BusinessException(AuthErrorCode.OAUTH_APPLE_TOKEN_INVALID);
                    })
                    .onStatus(status -> status.isError(), (request, res) -> {
                        throw new BusinessException(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
                    })
                    .body(TokenResponse.class);
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            // 타임아웃·연결 실패 등 네트워크 오류
            throw new BusinessException(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
        }

        if (response == null || response.refreshToken() == null || response.refreshToken().isBlank()) {
            throw new BusinessException(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
        }
        return response.refreshToken();
    }

    private record TokenResponse(@JsonProperty("refresh_token") String refreshToken) {
    }
}
