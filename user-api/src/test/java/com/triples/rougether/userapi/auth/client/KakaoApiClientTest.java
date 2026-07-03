package com.triples.rougether.userapi.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KakaoApiClientTest {

    private static final String BASE_URL = "https://kapi.kakao.com";
    private static final Long APP_ID = 1501738L;
    private static final String TOKEN_INFO_URL = BASE_URL + "/v1/user/access_token_info";
    private static final String USER_ME_URL = BASE_URL + "/v2/user/me";

    private MockRestServiceServer server;
    private KakaoApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KakaoApiClient(builder.baseUrl(BASE_URL).build(), APP_ID);
    }

    @Test
    void 정상_토큰이면_회원번호와_이메일을_반환한다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
                .andRespond(withSuccess("{\"app_id\":1501738}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_ME_URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
                .andRespond(withSuccess("{\"id\":123456789,\"kakao_account\":{\"email\":\"a@b.com\"}}",
                        MediaType.APPLICATION_JSON));

        KakaoUser user = client.fetchUser("tok");

        assertThat(user.id()).isEqualTo("123456789");
        assertThat(user.email()).isEqualTo("a@b.com");
        server.verify();
    }

    @Test
    void 이메일_미제공이면_email_은_null_이다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andRespond(withSuccess("{\"app_id\":1501738}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_ME_URL))
                .andRespond(withSuccess("{\"id\":123456789,\"kakao_account\":{}}", MediaType.APPLICATION_JSON));

        KakaoUser user = client.fetchUser("tok");

        assertThat(user.id()).isEqualTo("123456789");
        assertThat(user.email()).isNull();
    }

    @Test
    void 다른_앱에서_발급된_토큰_app_id_불일치면_401_로_거부한다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andRespond(withSuccess("{\"app_id\":999999}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchUser("tok"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_KAKAO_TOKEN_INVALID);
    }

    @Test
    void 카카오가_401이면_토큰_무효로_거부한다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.fetchUser("tok"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_KAKAO_TOKEN_INVALID);
    }

    @Test
    void 카카오가_5xx면_UNAVAILABLE_로_변환한다() {
        server.expect(requestTo(TOKEN_INFO_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.fetchUser("tok"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_KAKAO_UNAVAILABLE);
    }
}
