package com.triples.rougether.infra.llm;

import java.net.http.HttpClient;
import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// 외부 AI 추론만 분리함. 도메인 프롬프트·집계·소유권·결과 저장과 폴백은 기존 호출자가 소유함.
// 공급자 재시도는 FastAPI가 소유하므로 내부 HTTP 요청은 자동 재전송하지 않음.
@Component
@ConditionalOnProperty(name = "ai.service.enabled", havingValue = "true")
public class AiServiceClient implements LlmClient, EmbeddingClient {
    static final int MAX_INPUTS = 128;
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private final RestClient client;
    private final AiServiceProperties service;
    private final LlmProperties llm;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Autowired
    public AiServiceClient(AiServiceProperties service, LlmProperties llm) {
        this(restClient(service), service, llm);
    }

    AiServiceClient(RestClient client, AiServiceProperties service, LlmProperties llm) {
        this.client = client;
        this.service = service;
        this.llm = llm;
    }

    private static RestClient restClient(AiServiceProperties service) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER);
        if (service.caCertificateBase64() != null && !service.caCertificateBase64().isBlank()) {
            http.sslContext(privateTrust(service.caCertificateBase64()));
        }
        var factory = new JdkClientHttpRequestFactory(http.build());
        factory.setReadTimeout(service.timeout());
        return RestClient.builder().baseUrl(service.baseUrl()).requestFactory(factory).build();
    }

    // 내부 AI 연결에만 전용 CA를 적용함. JVM 전체 신뢰 저장소와 호스트명 검증은 변경하지 않음.
    static SSLContext privateTrust(String encodedCertificate) {
        try {
            var certificate = CertificateFactory.getInstance("X.509").generateCertificate(
                    new ByteArrayInputStream(Base64.getDecoder().decode(encodedCertificate)));
            var store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            store.setCertificateEntry("rougether-ai", certificate);
            var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            var context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            return context;
        } catch (Exception e) {
            throw new IllegalArgumentException("AI service CA 인증서 형식이 올바르지 않음");
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String complete(LlmChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", llm.model());
        body.put("systemPrompt", request.systemPrompt());
        body.put("userPrompt", request.userPrompt());
        body.put("maxTokens", request.maxTokens() == null ? llm.maxTokens() : request.maxTokens());
        body.put("temperature", request.temperature() == null ? llm.temperature() : request.temperature());
        body.put("jsonMode", llm.jsonMode());
        body.put("reasoningEffort", llm.reasoningEffort() == null || llm.reasoningEffort().isBlank() ? null : llm.reasoningEffort());
        JsonNode response = post("/internal/v1/completions", body);
        validateModel(response, llm.model());
        JsonNode content = response.path("content");
        if (!content.isString() || content.asString().isBlank()) {
            throw new LlmException("AI service의 completion 응답 형식 오류", false);
        }
        return content.asString();
    }

    @Override
    public List<float[]> embed(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        Integer dimensions = llm.embeddingDimensions();
        if (dimensions == null || dimensions < 1 || dimensions > 3072) {
            throw new LlmException("AI service 임베딩 차원 설정 오류", false);
        }
        List<float[]> result = new ArrayList<>(inputs.size());
        for (int from = 0; from < inputs.size(); from += MAX_INPUTS) {
            List<String> chunk = inputs.subList(from, Math.min(inputs.size(), from + MAX_INPUTS));
            JsonNode response = post("/internal/v1/embeddings", Map.of(
                    "model", llm.embeddingModel(), "dimensions", dimensions, "inputs", chunk));
            validateModel(response, llm.embeddingModel());
            JsonNode vectors = response.path("vectors");
            if (!vectors.isArray() || vectors.size() != chunk.size()) {
                throw new LlmException("AI service의 임베딩 응답 개수 오류", false);
            }
            for (JsonNode vector : vectors) {
                if (!vector.isArray() || vector.size() != dimensions) {
                    throw new LlmException("AI service의 임베딩 차원 오류", false);
                }
                float[] values = new float[dimensions];
                for (int i = 0; i < dimensions; i++) {
                    if (!vector.get(i).isNumber()) {
                        throw new LlmException("AI service의 임베딩 숫자 형식 오류", false);
                    }
                    values[i] = (float) vector.get(i).asDouble();
                    if (!Float.isFinite(values[i])) {
                        throw new LlmException("AI service의 임베딩 유한값 검증 실패", false);
                    }
                }
                result.add(values);
            }
        }
        return List.copyOf(result);
    }

    private void validateModel(JsonNode response, String expected) {
        if (!expected.equals(response.path("model").asString())) {
            // 잘못된 모델로 만든 회고가 정본으로 저장되지 않도록 설정 장애로 분류함.
            throw new LlmAuthException("AI service 모델 설정 불일치", null);
        }
    }

    private JsonNode post(String path, Object payload) {
        try {
            return client.post().uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + service.token())
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsBytes(payload))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        int limit = status == 200 ? MAX_RESPONSE_BYTES : 65536;
                        byte[] bytes = response.getBody().readNBytes(limit + 1);
                        if (bytes.length > limit) {
                            throw new LlmException("AI service 응답 크기 초과", false);
                        }
                        JsonNode body;
                        try {
                            body = mapper.readTree(bytes);
                        } catch (RuntimeException e) {
                            if (status == 401 || status == 403) {
                                throw new LlmAuthException("AI service 인증 실패", null);
                            }
                            throw new LlmException("AI service 응답 JSON 오류 status=" + status, status >= 500);
                        }
                        String code = body == null ? "" : body.path("code").asString();
                        if (status == 401 || status == 403 || "AI_PROVIDER_AUTH_FAILED".equals(code)
                                || "AI_MODEL_MISMATCH".equals(code)) {
                            throw new LlmAuthException("AI service 인증 또는 모델 설정 오류 status=" + status, null);
                        }
                        if (status != 200) {
                            throw new LlmException("AI service 호출 실패 status=" + status, status == 429 || status >= 500);
                        }
                        if (body == null || !body.isObject()) {
                            throw new LlmException("AI service 응답 JSON 객체 누락", false);
                        }
                        return body;
                    });
        } catch (RestClientException e) {
            throw new LlmException("AI service 네트워크 호출 실패", true, e);
        }
    }
}
