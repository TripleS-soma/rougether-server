package com.triples.rougether.infra.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleLlmClientTest {

    private static final String BASE_URL = "https://api.openai.com/v1";
    private static final String URL = BASE_URL + "/chat/completions";
    private static final String OK_BODY = """
            {"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"{\\"summary\\":\\"ok\\"}"}}]}""";

    private MockRestServiceServer server;
    private List<Duration> sleeps;
    private OpenAiCompatibleLlmClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sleeps = new ArrayList<>();
        client = clientWith(builder, true);
    }

    private OpenAiCompatibleLlmClient clientWith(RestClient.Builder builder, boolean jsonMode) {
        LlmProperties properties = new LlmProperties(BASE_URL, "gpt-5.6-luna", "sk-test",
                Duration.ofSeconds(30), null, 800, jsonMode, "low", 2, Duration.ofSeconds(1),
                "text-embedding-3-small", 512);
        return new OpenAiCompatibleLlmClient(builder.baseUrl(BASE_URL).build(), properties, sleeps::add);
    }

    @Test
    void Bearer_헤더와_모델_메시지로_호출하고_content를_돌려준다() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-test"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.model").value("gpt-5.6-luna"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.reasoning_effort").value("low"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("너는 회고 코치다"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("이번 주 기록"))
                .andExpect(jsonPath("$.max_completion_tokens").value(800))
                .andExpect(jsonPath("$.max_tokens").doesNotExist())
                // GPT-5 계열은 temperature 를 거부하므로 설정이 없으면 보내지 않는다
                .andExpect(jsonPath("$.temperature").doesNotExist())
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        String content = client.complete(LlmChatRequest.of("너는 회고 코치다", "이번 주 기록"));

        assertThat(content).isEqualTo("{\"summary\":\"ok\"}");
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void 요청별_maxTokens_temperature가_설정_기본값을_덮어쓴다() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.max_completion_tokens").value(200))
                .andExpect(jsonPath("$.temperature").value(0.0))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        client.complete(new LlmChatRequest(null, "q", 200, 0.0));

        server.verify();
    }

    @Test
    void jsonMode가_꺼져_있으면_response_format을_보내지_않는다() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleLlmClient plain = clientWith(builder, false);
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.response_format").doesNotExist())
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        plain.complete(LlmChatRequest.of("s", "u"));

        server.verify();
    }

    @Test
    void 시스템_프롬프트가_없으면_user_메시지_하나만_보낸다() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        client.complete(LlmChatRequest.of(null, "q"));

        server.verify();
    }

    @Test
    void _429는_지수_백오프로_재시도한_뒤_성공하면_결과를_돌려준다() {
        server.expect(times(2), requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        String content = client.complete(LlmChatRequest.of("s", "u"));

        assertThat(content).isEqualTo("{\"summary\":\"ok\"}");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
        server.verify();
    }

    @Test
    void _5xx가_재시도_횟수를_넘기면_retryable_LlmException() {
        server.expect(times(3), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.complete(LlmChatRequest.of("s", "u")))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).isRetryable()).isTrue());
        assertThat(sleeps).hasSize(2);
        server.verify();
    }

    @Test
    void 네트워크_오류도_재시도_대상이다() {
        server.expect(times(3), requestTo(URL)).andRespond(withException(new IOException("timeout")));

        assertThatThrownBy(() -> client.complete(LlmChatRequest.of("s", "u")))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).isRetryable()).isTrue());
        server.verify();
    }

    @Test
    void _400은_재시도_없이_즉시_non_retryable_LlmException() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.complete(LlmChatRequest.of("s", "u")))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).isRetryable()).isFalse());
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void _401_403은_재시도_없이_LlmAuthException() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.complete(LlmChatRequest.of("s", "u")))
                .isInstanceOf(LlmAuthException.class)
                .satisfies(e -> assertThat(((LlmException) e).isRetryable()).isFalse());
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void content가_비어_있으면_non_retryable_LlmException() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"}}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.complete(LlmChatRequest.of("s", "u")))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).isRetryable()).isFalse());
        server.verify();
    }

    @Test
    void choices가_없으면_non_retryable_LlmException() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"error\":\"nope\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.complete(LlmChatRequest.of("s", "u")))
                .isInstanceOf(LlmException.class);
        server.verify();
    }
}
