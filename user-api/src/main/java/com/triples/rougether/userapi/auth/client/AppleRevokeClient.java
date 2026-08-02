package com.triples.rougether.userapi.auth.client;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// 회원탈퇴 시 저장해 둔 refresh token으로 애플 연동을 해제(revoke)함.
@Component
public class AppleRevokeClient {

    private static final String APPLE_BASE_URL = "https://appleid.apple.com";

    private final RestClient restClient;
    private final AppleClientSecretFactory clientSecretFactory;

    @Autowired
    public AppleRevokeClient(AppleClientSecretFactory clientSecretFactory) {
        this(RestClient.builder().baseUrl(APPLE_BASE_URL).build(), clientSecretFactory);
    }

    // 테스트에서 MockRestServiceServer로 바인딩한 RestClient를 주입하기 위한 생성자.
    AppleRevokeClient(RestClient restClient, AppleClientSecretFactory clientSecretFactory) {
        this.restClient = restClient;
        this.clientSecretFactory = clientSecretFactory;
    }

    public void revoke(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientSecretFactory.clientId());
        form.add("client_secret", clientSecretFactory.create());
        form.add("token", refreshToken);
        form.add("token_type_hint", "refresh_token");
        try {
            restClient.post()
                    .uri("/auth/revoke")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new BusinessException(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
                    })
                    .toBodilessEntity();
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            // 타임아웃·연결 실패 등 네트워크 오류
            throw new BusinessException(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
        }
    }
}
