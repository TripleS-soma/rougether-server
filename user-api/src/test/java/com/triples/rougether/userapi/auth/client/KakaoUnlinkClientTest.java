package com.triples.rougether.userapi.auth.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
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

class KakaoUnlinkClientTest {

    private static final String BASE_URL = "https://kapi.kakao.com";
    private static final String UNLINK_URL = BASE_URL + "/v1/user/unlink";

    private MockRestServiceServer server;
    private KakaoUnlinkClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KakaoUnlinkClient(builder.baseUrl(BASE_URL).build(), "admin-key");
    }

    @Test
    void Admin_key_헤더와_회원번호로_unlink_를_호출한다() {
        server.expect(requestTo(UNLINK_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "KakaoAK admin-key"))
                .andExpect(content().formData(org.springframework.util.CollectionUtils.toMultiValueMap(
                        java.util.Map.of("target_id_type", java.util.List.of("user_id"),
                                "target_id", java.util.List.of("123456789")))))
                .andRespond(withSuccess("{\"id\":123456789}", MediaType.APPLICATION_JSON));

        assertThatCode(() -> client.unlink("123456789")).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void 카카오가_오류를_주면_UNAVAILABLE_로_변환한다() {
        server.expect(requestTo(UNLINK_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.unlink("123456789"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_KAKAO_UNAVAILABLE);
    }

    @Test
    void Admin_key_미설정이면_호출_없이_UNAVAILABLE_로_실패한다() {
        // fail-closed: 시크릿 없는 환경에서 unlink 가 조용히 건너뛰어지지 않아야 함.
        KakaoUnlinkClient noKey = new KakaoUnlinkClient(
                RestClient.builder().baseUrl(BASE_URL).build(), " ");

        assertThatThrownBy(() -> noKey.unlink("123456789"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.OAUTH_KAKAO_UNAVAILABLE);
    }
}
