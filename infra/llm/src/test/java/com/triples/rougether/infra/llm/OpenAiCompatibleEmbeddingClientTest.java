package com.triples.rougether.infra.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleEmbeddingClientTest {

    private static final String BASE_URL = "https://api.openai.com/v1";
    private static final String URL = BASE_URL + "/embeddings";
    // data 가 index 역순으로 와도 입력 순서대로 매핑되는지 보기 위해 일부러 뒤집어 둔다
    private static final String TWO_VECTORS_REVERSED = """
            {"object":"list","data":[
              {"object":"embedding","index":1,"embedding":[0.5,0.5,0.0]},
              {"object":"embedding","index":0,"embedding":[1.0,0.0,0.0]}
            ],"model":"text-embedding-3-small","usage":{"prompt_tokens":4,"total_tokens":4}}""";

    private MockRestServiceServer server;
    private List<Duration> sleeps;
    private OpenAiCompatibleEmbeddingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sleeps = new ArrayList<>();
        client = clientWith(builder, 512);
    }

    private OpenAiCompatibleEmbeddingClient clientWith(RestClient.Builder builder, Integer dimensions) {
        LlmProperties properties = new LlmProperties(BASE_URL, "gpt-5.6-luna", "sk-test",
                Duration.ofSeconds(30), null, 800, true, "low", 2, Duration.ofSeconds(1),
                "text-embedding-3-small", dimensions);
        return new OpenAiCompatibleEmbeddingClient(builder.baseUrl(BASE_URL).build(), properties, sleeps::add);
    }

    @Test
    void Bearer_헤더와_모델_input_dimensions로_호출하고_index_순으로_매핑한다() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-test"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.model").value("text-embedding-3-small"))
                .andExpect(jsonPath("$.input.length()").value(2))
                .andExpect(jsonPath("$.input[0]").value("아침 운동"))
                .andExpect(jsonPath("$.input[1]").value("헬스"))
                .andExpect(jsonPath("$.encoding_format").value("float"))
                .andExpect(jsonPath("$.dimensions").value(512))
                .andRespond(withSuccess(TWO_VECTORS_REVERSED, MediaType.APPLICATION_JSON));

        List<float[]> vectors = client.embed(List.of("아침 운동", "헬스"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(1.0f, 0.0f, 0.0f);
        assertThat(vectors.get(1)).containsExactly(0.5f, 0.5f, 0.0f);
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void dimensions가_0이면_보내지_않는다() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleEmbeddingClient plain = clientWith(builder, 0);
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.dimensions").doesNotExist())
                .andRespond(withSuccess(TWO_VECTORS_REVERSED, MediaType.APPLICATION_JSON));

        plain.embed(List.of("a", "b"));

        server.verify();
    }

    @Test
    void 빈_입력이면_호출_없이_빈_목록을_돌려준다() {
        assertThat(client.embed(List.of())).isEmpty();
        server.verify();
    }

    @Test
    void 응답_개수가_입력과_다르면_non_retryable_LlmException() {
        server.expect(requestTo(URL)).andRespond(withSuccess(TWO_VECTORS_REVERSED, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embed(List.of("a", "b", "c")))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).isRetryable()).isFalse())
                .hasMessageContaining("개수 불일치");
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void 응답_index가_중복이면_실패한다() {
        assertMalformedResponseFails("""
                {"data":[{"index":0,"embedding":[1.0]},{"index":0,"embedding":[2.0]}]}""");
    }

    @Test
    void 응답_index가_범위를_벗어나면_실패한다() {
        assertMalformedResponseFails("""
                {"data":[{"index":0,"embedding":[1.0]},{"index":5,"embedding":[2.0]}]}""");
    }

    @Test
    void 응답_embedding이_배열이_아니면_실패한다() {
        assertMalformedResponseFails("""
                {"data":[{"index":0,"embedding":[1.0]},{"index":1,"embedding":"oops"}]}""");
    }

    private void assertMalformedResponseFails(String body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embed(List.of("a", "b")))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).isRetryable()).isFalse())
                .hasMessageContaining("index");
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void null_입력도_호출_없이_빈_목록을_돌려준다() {
        assertThat(client.embed(null)).isEmpty();
        server.verify();
    }

    @Test
    void _429는_지수_백오프로_재시도한_뒤_성공하면_결과를_돌려준다() {
        server.expect(times(2), requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withSuccess(TWO_VECTORS_REVERSED, MediaType.APPLICATION_JSON));

        List<float[]> vectors = client.embed(List.of("a", "b"));

        assertThat(vectors).hasSize(2);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
        server.verify();
    }

    @Test
    void _5xx가_재시도_횟수를_넘기면_retryable_LlmException() {
        server.expect(times(3), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.embed(List.of("a", "b")))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).isRetryable()).isTrue());
        assertThat(sleeps).hasSize(2);
        server.verify();
    }

    @Test
    void _401은_재시도_없이_LlmAuthException() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.embed(List.of("a", "b")))
                .isInstanceOf(LlmAuthException.class);
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void _2048개를_넘으면_청크로_나눠_호출하고_순서대로_합친다() {
        int total = OpenAiCompatibleEmbeddingClient.MAX_INPUTS_PER_CALL + 3;
        List<String> inputs = IntStream.range(0, total).mapToObj(i -> "t" + i).toList();
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.input.length()").value(OpenAiCompatibleEmbeddingClient.MAX_INPUTS_PER_CALL))
                .andExpect(jsonPath("$.input[0]").value("t0"))
                .andRespond(withSuccess(vectorsBody(0, OpenAiCompatibleEmbeddingClient.MAX_INPUTS_PER_CALL),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.input.length()").value(3))
                .andExpect(jsonPath("$.input[0]").value("t" + OpenAiCompatibleEmbeddingClient.MAX_INPUTS_PER_CALL))
                .andRespond(withSuccess(vectorsBody(OpenAiCompatibleEmbeddingClient.MAX_INPUTS_PER_CALL, 3),
                        MediaType.APPLICATION_JSON));

        List<float[]> vectors = client.embed(inputs);

        assertThat(vectors).hasSize(total);
        // 각 벡터의 첫 성분에 전체 순번을 심어 두어 청크 경계에서 순서가 유지되는지 확인
        assertThat(vectors.get(0)[0]).isEqualTo(0f);
        assertThat(vectors.get(OpenAiCompatibleEmbeddingClient.MAX_INPUTS_PER_CALL - 1)[0])
                .isEqualTo((float) (OpenAiCompatibleEmbeddingClient.MAX_INPUTS_PER_CALL - 1));
        assertThat(vectors.get(OpenAiCompatibleEmbeddingClient.MAX_INPUTS_PER_CALL)[0])
                .isEqualTo((float) OpenAiCompatibleEmbeddingClient.MAX_INPUTS_PER_CALL);
        assertThat(vectors.get(total - 1)[0]).isEqualTo((float) (total - 1));
        server.verify();
    }

    // offset 부터 count 개의 벡터. embedding[0] 에 전체 순번(offset+i)을 넣고 index 는 청크 내 순번(i)
    private static String vectorsBody(int offset, int count) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"index\":").append(i).append(",\"embedding\":[").append((float) (offset + i)).append(",0.0]}");
        }
        return sb.append("]}").toString();
    }
}
