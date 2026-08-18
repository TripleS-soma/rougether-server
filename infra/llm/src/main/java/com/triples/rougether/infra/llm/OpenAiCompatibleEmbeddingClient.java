package com.triples.rougether.infra.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// OpenAI 호환 POST {base-url}/embeddings 클라이언트. base-url·api-key·timeout·재시도 정책은 채팅 클라이언트와 공유하고
// 모델(llm.embedding-model)·차원(llm.embedding-dimensions)만 따로 둔다. 한 호출 입력 상한(2048)을 넘으면 내부에서 나눠 호출한다.
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${llm.api-key:}')")
@Component
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingClient.class);
    private static final String EMBEDDINGS_PATH = "/embeddings";
    private static final String WHAT = "Embedding API";
    // OpenAI embeddings 의 한 요청 최대 input 개수
    static final int MAX_INPUTS_PER_CALL = 2048;

    private final RestClient restClient;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final Consumer<Duration> sleeper;

    @Autowired
    public OpenAiCompatibleEmbeddingClient(LlmProperties properties) {
        this(OpenAiHttpSupport.restClient(properties), properties, OpenAiHttpSupport::sleepQuietly);
    }

    // 테스트에서 MockRestServiceServer로 바인딩한 RestClient와 즉시 반환 sleeper를 주입하기 위한 생성자.
    OpenAiCompatibleEmbeddingClient(RestClient restClient, LlmProperties properties, Consumer<Duration> sleeper) {
        this.restClient = restClient;
        this.properties = properties;
        this.sleeper = sleeper;
    }

    @Override
    public List<float[]> embed(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = new ArrayList<>(inputs.size());
        for (int from = 0; from < inputs.size(); from += MAX_INPUTS_PER_CALL) {
            List<String> chunk = inputs.subList(from, Math.min(from + MAX_INPUTS_PER_CALL, inputs.size()));
            String body = buildBody(chunk);
            vectors.addAll(OpenAiHttpSupport.withRetry(properties, sleeper, log, WHAT,
                    () -> callOnce(body, chunk.size())));
        }
        return List.copyOf(vectors);
    }

    private List<float[]> callOnce(String body, int expectedCount) {
        String raw;
        try {
            raw = restClient.post()
                    .uri(EMBEDDINGS_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw OpenAiHttpSupport.translate(e, WHAT);
        }
        return extractVectors(raw, expectedCount);
    }

    private String buildBody(List<String> inputs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.embeddingModel());
        payload.put("input", inputs);
        payload.put("encoding_format", "float");
        // dimensions 는 양수일 때만 보낸다(미지원 공급자는 0 으로 끈다)
        Integer dimensions = properties.embeddingDimensions();
        if (dimensions != null && dimensions > 0) {
            payload.put("dimensions", properties.embeddingDimensions());
        }
        return objectMapper.writeValueAsString(payload);
    }

    // data[] 를 index 순으로 정렬해 벡터로 매핑. 개수 불일치·빈 응답은 재시도해도 같으므로 non-retryable.
    private List<float[]> extractVectors(String raw, int expectedCount) {
        if (raw == null || raw.isBlank()) {
            throw new LlmException("Embedding 응답 본문이 비어 있음", false);
        }
        JsonNode data;
        try {
            data = objectMapper.readTree(raw).path("data");
        } catch (RuntimeException e) {
            throw new LlmException("Embedding 응답 JSON 파싱 실패", false, e);
        }
        if (!data.isArray() || data.size() != expectedCount) {
            throw new LlmException("Embedding 응답 개수 불일치 expected=" + expectedCount
                    + " actual=" + (data.isArray() ? data.size() : 0), false);
        }
        float[][] ordered = new float[expectedCount][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            JsonNode embedding = item.path("embedding");
            if (index < 0 || index >= expectedCount || ordered[index] != null || !embedding.isArray()) {
                throw new LlmException("Embedding 응답 index/embedding 형식 오류 index=" + index, false);
            }
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            ordered[index] = vector;
        }
        return List.of(ordered);
    }
}
