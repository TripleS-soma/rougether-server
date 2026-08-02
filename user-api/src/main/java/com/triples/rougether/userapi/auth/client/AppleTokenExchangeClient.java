package com.triples.rougether.userapi.auth.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// 애플 authorizationCode → refresh token 교환. 회원탈퇴 시 연동 해제(revoke)에 쓸 토큰을 확보함.
@Component
public class AppleTokenExchangeClient {

    private static final Logger log = LoggerFactory.getLogger(AppleTokenExchangeClient.class);
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
                        log.warn("apple token exchange rejected: status={}, body={}",
                                res.getStatusCode(), readBody(res));
                        throw new BusinessException(AuthErrorCode.OAUTH_APPLE_TOKEN_INVALID);
                    })
                    .onStatus(status -> status.isError(), (request, res) -> {
                        log.warn("apple token exchange failed: status={}, body={}",
                                res.getStatusCode(), readBody(res));
                        throw new BusinessException(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
                    })
                    .body(TokenResponse.class);
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            // 타임아웃·연결 실패 등 네트워크 오류
            log.warn("apple token exchange request error: {}", e.getMessage(), e);
            throw new BusinessException(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
        }

        if (response == null || response.refreshToken() == null || response.refreshToken().isBlank()) {
            log.warn("apple token exchange returned no refresh token: response={}", response);
            throw new BusinessException(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
        }
        return response.refreshToken();
    }

    // 애플 에러 응답 본문(예: {"error":"invalid_grant"})을 로그로 남기기 위해 읽음.
    private String readBody(ClientHttpResponse response) {
        try {
            return StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<unreadable: " + e.getMessage() + ">";
        }
    }

    private record TokenResponse(@JsonProperty("refresh_token") String refreshToken) {
    }
}
