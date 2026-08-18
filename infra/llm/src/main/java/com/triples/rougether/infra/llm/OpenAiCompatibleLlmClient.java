package com.triples.rougether.infra.llm;

import java.time.Duration;
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

// OpenAI 호환 POST {base-url}/chat/completions 클라이언트(OpenAI·NVIDIA NIM 등 동일 스키마).
// 429/5xx/네트워크 오류는 지수 백오프로 llm.max-retries 회 재시도하고, 그 외 4xx는 즉시 실패시킨다(OpenAiHttpSupport 공유).
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${llm.api-key:}')")
@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String WHAT = "LLM API";

    private final RestClient restClient;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final Consumer<Duration> sleeper;

    @Autowired
    public OpenAiCompatibleLlmClient(LlmProperties properties) {
        this(OpenAiHttpSupport.restClient(properties), properties, OpenAiHttpSupport::sleepQuietly);
    }

    // 테스트에서 MockRestServiceServer로 바인딩한 RestClient와 즉시 반환 sleeper를 주입하기 위한 생성자.
    OpenAiCompatibleLlmClient(RestClient restClient, LlmProperties properties, Consumer<Duration> sleeper) {
        this.restClient = restClient;
        this.properties = properties;
        this.sleeper = sleeper;
    }

    @Override
    public String complete(LlmChatRequest request) {
        String body = buildBody(request);
        return OpenAiHttpSupport.withRetry(properties, sleeper, log, WHAT, () -> callOnce(body));
    }

    private String callOnce(String body) {
        String raw;
        try {
            raw = restClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    // 4xx/5xx는 retrieve() 기본 처리로 RestClientResponseException이 던져지고 아래에서 상태코드별로 분류한다.
                    .body(String.class);
        } catch (RestClientException e) {
            throw OpenAiHttpSupport.translate(e, WHAT);
        }
        return extractContent(raw);
    }

    private String buildBody(LlmChatRequest request) {
        List<Map<String, String>> messages = request.systemPrompt() == null || request.systemPrompt().isBlank()
                ? List.of(Map.of("role", "user", "content", request.userPrompt()))
                : List.of(
                        Map.of("role", "system", "content", request.systemPrompt()),
                        Map.of("role", "user", "content", request.userPrompt()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.model());
        payload.put("messages", messages);
        // GPT-5 계열은 max_tokens 를 거부하고 max_completion_tokens 만 받는다(구형 모델도 이 이름을 받아들임).
        payload.put("max_completion_tokens", request.maxTokens() != null ? request.maxTokens() : properties.maxTokens());
        Double temperature = request.temperature() != null ? request.temperature() : properties.temperature();
        if (temperature != null) {
            payload.put("temperature", temperature);
        }
        if (properties.reasoningEffort() != null && !properties.reasoningEffort().isBlank()) {
            payload.put("reasoning_effort", properties.reasoningEffort());
        }
        if (properties.jsonMode()) {
            // OpenAI JSON mode — 유효한 JSON 객체만 돌려주게 강제(프롬프트에 "JSON" 언급이 있어야 함, 시스템 프롬프트가 보장).
            payload.put("response_format", Map.of("type", "json_object"));
        }
        return objectMapper.writeValueAsString(payload);
    }

    private String extractContent(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new LlmException("LLM 응답 본문이 비어 있음", false);
        }
        JsonNode content;
        try {
            content = objectMapper.readTree(raw).path("choices").path(0).path("message").path("content");
        } catch (RuntimeException e) {
            throw new LlmException("LLM 응답 JSON 파싱 실패", false, e);
        }
        if (content.isMissingNode() || content.isNull() || content.asString().isBlank()) {
            throw new LlmException("LLM 응답에 choices[0].message.content가 없음", false);
        }
        return content.asString();
    }
}
