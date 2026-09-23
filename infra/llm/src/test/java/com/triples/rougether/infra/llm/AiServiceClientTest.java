package com.triples.rougether.infra.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AiServiceClientTest {
    private static final String BASE = "http://127.0.0.1:8090";
    private static final String TOKEN = "internal-test-token-12345678901234567890";
    private MockRestServiceServer server;
    private AiServiceClient client;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiServiceClient(builder.build(),
                new AiServiceProperties(true, BASE, TOKEN, Duration.ofSeconds(95), false),
                new LlmProperties("https://provider.invalid/v1", "chat-model", "provider-key-must-not-be-forwarded",
                        Duration.ofSeconds(30), null, 800, true, "low", 2, Duration.ofSeconds(1), "embed-model", 2));
    }

    @Test
    void 내부_토큰과_기존_프롬프트_옵션을_전송하고_본문을_반환한다() {
        server.expect(requestTo(BASE + "/internal/v1/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(jsonPath("$.model").value("chat-model"))
                .andExpect(jsonPath("$.systemPrompt").value("한국어 JSON 회고"))
                .andExpect(jsonPath("$.userPrompt").value("일주일 기록"))
                .andExpect(jsonPath("$.maxTokens").value(1000))
                .andExpect(jsonPath("$.temperature").value(0.5))
                .andExpect(jsonPath("$.jsonMode").value(true))
                .andExpect(jsonPath("$.reasoningEffort").value("low"))
                .andRespond(withSuccess("{\"model\":\"chat-model\",\"content\":\"회고 결과\"}", MediaType.APPLICATION_JSON));
        assertThat(client.complete(new LlmChatRequest("한국어 JSON 회고", "일주일 기록", 1000, 0.5))).isEqualTo("회고 결과");
        server.verify();
    }

    @Test
    void 임베딩을_128개씩_나누고_순서를_유지한다() {
        server.expect(requestTo(BASE + "/internal/v1/embeddings"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(jsonPath("$.inputs.length()").value(128))
                .andExpect(jsonPath("$.dimensions").value(2))
                .andRespond(withSuccess(vectors(128), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/internal/v1/embeddings"))
                .andExpect(jsonPath("$.inputs[0]").value("title-128"))
                .andRespond(withSuccess("{\"model\":\"embed-model\",\"vectors\":[[9,8]]}", MediaType.APPLICATION_JSON));
        List<float[]> result = client.embed(IntStream.range(0, 129).mapToObj(i -> "title-" + i).toList());
        assertThat(result).hasSize(129);
        assertThat(result.get(0)).containsExactly(0, 1);
        assertThat(result.get(127)).containsExactly(127, 128);
        assertThat(result.get(128)).containsExactly(9, 8);
        server.verify();
    }

    @Test
    void 빈_입력은_네트워크_호출이_없다() {
        assertThat(client.embed(List.of())).isEmpty();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503, 504})
    void 일시_오류여도_내부_HTTP를_재전송하지_않는다(int status) {
        server.expect(requestTo(BASE + "/internal/v1/completions"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"AI_PROVIDER_UNAVAILABLE\"}"));
        assertThatThrownBy(() -> client.complete(LlmChatRequest.of(null, "request")))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.isRetryable()).isTrue());
        server.verify();
    }

    @Test
    void 네트워크_실패는_원본_호출자로_한번만_전달한다() {
        server.expect(requestTo(BASE + "/internal/v1/embeddings"))
                .andRespond(withException(new IOException("unavailable")));
        assertThatThrownBy(() -> client.embed(List.of("운동"))).isInstanceOf(LlmException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"AI_PROVIDER_AUTH_FAILED", "AI_MODEL_MISMATCH"})
    void 공급자_설정_장애는_회고_FALLBACK으로_저장하지_않게_구분한다(String code) {
        server.expect(requestTo(BASE + "/internal/v1/completions"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"" + code + "\"}"));
        assertThatThrownBy(() -> client.complete(LlmChatRequest.of(null, "request"))).isInstanceOf(LlmAuthException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void 인증_오류는_HTML_응답이어도_설정_장애이다(int status) {
        server.expect(requestTo(BASE + "/internal/v1/completions"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body("<html>denied</html>"));
        assertThatThrownBy(() -> client.complete(LlmChatRequest.of(null, "request"))).isInstanceOf(LlmAuthException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"model\":\"embed-model\",\"vectors\":[]}",
        "{\"model\":\"embed-model\",\"vectors\":[[1]]}",
        "{\"model\":\"embed-model\",\"vectors\":[[\"1\",2]]}",
        "{\"model\":\"embed-model\",\"vectors\":[[1e100,2]]}",
        "{\"model\":\"different-model\",\"vectors\":[[1,2]]}", "null", "malformed"
    })
    void 벡터_개수_차원_숫자_모델을_검증한다(String response) {
        server.expect(requestTo(BASE + "/internal/v1/embeddings"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.embed(List.of("운동"))).isInstanceOf(LlmException.class);
        server.verify();
    }

    private String vectors(int count) {
        return "{\"model\":\"embed-model\",\"vectors\":[" + String.join(",",
                IntStream.range(0, count).mapToObj(i -> "[" + i + "," + (i + 1) + "]").toList()) + "]}";
    }
}
