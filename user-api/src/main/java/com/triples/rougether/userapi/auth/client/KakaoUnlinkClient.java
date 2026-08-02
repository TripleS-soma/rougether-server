package com.triples.rougether.userapi.auth.client;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.config.KakaoProperties;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// 회원탈퇴 시 Admin key 서버-투-서버 unlink. 카카오 측 연동(동의)을 해제함.
@Component
public class KakaoUnlinkClient {

    private final RestClient restClient;
    private final String adminKey;

    @Autowired
    public KakaoUnlinkClient(KakaoProperties properties) {
        this(RestClient.builder().baseUrl(properties.apiBaseUrl()).build(), properties.adminKey());
    }

    // 테스트에서 MockRestServiceServer로 바인딩한 RestClient를 주입하기 위한 생성자.
    KakaoUnlinkClient(RestClient restClient, String adminKey) {
        this.restClient = restClient;
        this.adminKey = adminKey;
    }

    public void unlink(String providerUserId) {
        // fail-closed: Admin key 미설정 환경에서 카카오 호출을 조용히 건너뛰지 않고 실패시킴.
        if (adminKey == null || adminKey.isBlank()) {
            throw new BusinessException(AuthErrorCode.OAUTH_KAKAO_UNAVAILABLE);
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("target_id_type", "user_id");
        form.add("target_id", providerUserId);
        try {
            restClient.post()
                    .uri("/v1/user/unlink")
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + adminKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new BusinessException(AuthErrorCode.OAUTH_KAKAO_UNAVAILABLE);
                    })
                    .toBodilessEntity();
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            // 타임아웃·연결 실패 등 네트워크 오류
            throw new BusinessException(AuthErrorCode.OAUTH_KAKAO_UNAVAILABLE);
        }
    }
}
