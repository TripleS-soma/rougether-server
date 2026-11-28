package com.triples.rougether.batch.moderation;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// TypeSafe Jev(POST {base-url}/v1/systemone) choice 질문 1개로 글 하나를 판정함(#478).
// 429·529·5xx·네트워크 오류는 max-retries 회 지수 백오프 재시도, 401·403 은 JevAuthException, 그 외 4xx 는 즉시 실패.
// 작성자 식별 정보는 보내지 않고 본문(과 댓글의 원 게시글 앞부분)만 보냄.
@Slf4j
@Component
public class JevClient {

    private static final String PATH = "/v1/systemone";
    private static final double PROBABILITY_SUM_TOLERANCE = 0.02;
    private static final int MODEL_MAX_LENGTH = 40;

    private final RestClient restClient;
    private final ContentScreeningProperties properties;
    private final Consumer<Duration> sleeper;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Autowired
    public JevClient(ContentScreeningProperties properties) {
        this(restClient(properties), properties, JevClient::sleepQuietly);
    }

    // 테스트에서 MockRestServiceServer 로 바인딩한 RestClient 와 즉시 반환 sleeper 를 주입하기 위한 생성자.
    JevClient(RestClient restClient, ContentScreeningProperties properties, Consumer<Duration> sleeper) {
        this.restClient = restClient;
        this.properties = properties;
        this.sleeper = sleeper;
    }

    public JevVerdict classify(String text, String context) {
        String body = buildBody(text, context);
        return withRetry(() -> parse(callOnce(body)));
    }

    private String callOnce(String body) {
        try {
            return restClient.post()
                    .uri(PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw translate(e);
        }
    }

    private String buildBody(String text, String context) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("text", text);
        if (context != null && !context.isBlank()) {
            state.put("context", context);
        }
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "choice");
        question.put("instructions", ScreeningCriteria.INSTRUCTIONS);
        question.put("criteria", ScreeningCriteria.CRITERIA);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.model());
        payload.put("state", state);
        payload.put("questions", Map.of(ScreeningCriteria.QUESTION, question));
        return objectMapper.writeValueAsString(payload);
    }

    // 항목 집합이 정의와 정확히 같고, 각 확률이 0~1 이며 합이 1 근처인 응답만 받음. 아니면 그 건을 판정하지 않음.
    JevVerdict parse(String raw) {
        JsonNode root;
        try {
            root = objectMapper.readTree(raw == null ? "" : raw);
        } catch (JacksonException e) {
            throw new JevException("Jev 응답 JSON 파싱 실패", false, e);
        }
        JsonNode probabilities = root.path("answers").path(ScreeningCriteria.QUESTION).path("probabilities");
        if (!probabilities.isObject() || probabilities.size() != ScreeningCriteria.CRITERIA.size()) {
            throw new JevException("Jev 응답 확률 항목이 정의와 다름", false);
        }
        Map<String, Double> values = new HashMap<>();
        double sum = 0;
        for (String key : ScreeningCriteria.CRITERIA.keySet()) {
            JsonNode node = probabilities.get(key);
            if (node == null || !node.isNumber()) {
                throw new JevException("Jev 응답에 항목 확률이 없음: " + key, false);
            }
            double value = node.asDouble();
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                throw new JevException("Jev 응답 확률이 범위를 벗어남: " + key, false);
            }
            values.put(key, value);
            sum += value;
        }
        if (Math.abs(sum - 1) > PROBABILITY_SUM_TOLERANCE) {
            throw new JevException("Jev 응답 확률 합이 1 이 아님: " + sum, false);
        }
        // 응답 모델명은 content_screenings.model(VARCHAR 40)에 저장됨. 비었거나 길면 요청한 고정 모델명을 씀.
        String reported = root.path("model").asString("");
        String model = reported.isBlank() || reported.length() > MODEL_MAX_LENGTH ? properties.model() : reported;
        int inputTokens = Math.max(0, root.path("usage").path("input_tokens").asInt(0));
        return new JevVerdict(model, values, inputTokens);
    }

    private <T> T withRetry(Supplier<T> call) {
        int maxAttempts = properties.maxRetries() + 1;
        JevException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.get();
            } catch (JevException e) {
                if (!e.isRetryable()) {
                    throw e;
                }
                last = e;
                if (attempt < maxAttempts) {
                    Duration backoff = properties.retryBackoff().multipliedBy(1L << (attempt - 1));
                    log.warn("Jev 호출 실패(시도 {}/{}) — {}ms 후 재시도: {}", attempt, maxAttempts,
                            backoff.toMillis(), e.getMessage());
                    sleeper.accept(backoff);
                }
            }
        }
        throw last;
    }

    private static JevException translate(RestClientException e) {
        if (e instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            if (status == 401 || status == 403) {
                return new JevAuthException("Jev 인증 실패 status=" + status, e);
            }
            // 529(Overloaded)는 5xx 범위라 함께 재시도함
            boolean retryable = status == 429 || response.getStatusCode().is5xxServerError();
            return new JevException("Jev 오류 status=" + status, retryable, e);
        }
        return new JevException("Jev 네트워크 오류: " + e.getMessage(), true, e);
    }

    private static RestClient restClient(ContentScreeningProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        return RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
