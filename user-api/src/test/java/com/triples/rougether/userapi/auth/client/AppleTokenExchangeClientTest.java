package com.triples.rougether.userapi.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AppleTokenExchangeClientTest {

    private static final String BASE_URL = "https://appleid.apple.com";
    private static final String TOKEN_URL = BASE_URL + "/auth/token";

    private MockRestServiceServer server;
    private AppleTokenExchangeClient client;

    @BeforeEach
    void setUp() {
        AppleClientSecretFactory factory = mock(AppleClientSecretFactory.class);
        when(factory.clientId()).thenReturn("com.triples.rougether");
        when(factory.create()).thenReturn("client-secret-jwt");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AppleTokenExchangeClient(builder.baseUrl(BASE_URL).build(), factory);
    }

    @Test
    void authorizationCode_를_form_으로_교환해_refresh_token_을_반환한다() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(org.springframework.util.CollectionUtils.toMultiValueMap(
                        java.util.Map.of(
                                "client_id", java.util.List.of("com.triples.rougether"),
                                "client_secret", java.util.List.of("client-secret-jwt"),
                                "grant_type", java.util.List.of("authorization_code"),
                                "code", java.util.List.of("authcode")))))
                .andRespond(withSuccess(
                        "{\"access_token\":\"at\",\"refresh_token\":\"rt\",\"id_token\":\"idt\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.exchangeRefreshToken("authcode")).isEqualTo("rt");
        server.verify();
    }

    @Test
    void 만료_재사용_등_잘못된_코드는_TOKEN_INVALID_로_거부한다() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\"}"));

        assertThatThrownBy(() -> client.exchangeRefreshToken("bad"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_APPLE_TOKEN_INVALID);
    }

    @Test
    void 애플이_5xx면_UNAVAILABLE_로_변환한다() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.exchangeRefreshToken("authcode"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
    }

    @Test
    void 응답에_refresh_token_이_없으면_UNAVAILABLE_로_실패한다() {
        // revoke 재료를 확보하지 못한 로그인은 성공으로 처리하지 않음.
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"at\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.exchangeRefreshToken("authcode"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
    }
}
