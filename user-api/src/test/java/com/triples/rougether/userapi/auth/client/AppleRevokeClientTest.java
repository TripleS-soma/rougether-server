package com.triples.rougether.userapi.auth.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AppleRevokeClientTest {

    private static final String BASE_URL = "https://appleid.apple.com";
    private static final String REVOKE_URL = BASE_URL + "/auth/revoke";

    private MockRestServiceServer server;
    private AppleRevokeClient client;

    @BeforeEach
    void setUp() {
        AppleClientSecretFactory factory = Mockito.mock(AppleClientSecretFactory.class);
        Mockito.when(factory.clientId()).thenReturn("com.triples.rougether");
        Mockito.when(factory.create()).thenReturn("client-secret-jwt");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AppleRevokeClient(builder.baseUrl(BASE_URL).build(), factory);
    }

    @Test
    void 저장된_refresh_token_으로_revoke_를_호출한다() {
        server.expect(requestTo(REVOKE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(org.springframework.util.CollectionUtils.toMultiValueMap(
                        java.util.Map.of(
                                "client_id", java.util.List.of("com.triples.rougether"),
                                "client_secret", java.util.List.of("client-secret-jwt"),
                                "token", java.util.List.of("rt"),
                                "token_type_hint", java.util.List.of("refresh_token")))))
                .andRespond(withSuccess());

        assertThatCode(() -> client.revoke("rt")).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void 애플이_오류를_주면_UNAVAILABLE_로_변환한다() {
        server.expect(requestTo(REVOKE_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.revoke("rt"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE);
    }
}
