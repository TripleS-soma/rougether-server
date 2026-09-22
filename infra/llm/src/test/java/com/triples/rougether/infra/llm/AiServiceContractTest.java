package com.triples.rougether.infra.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

// qa/ai-service-contract/run.py가 띄운 실제 FastAPI와 JVM HTTP 계약을 검증함. 외부 AI 호출은 없음.
@EnabledIfEnvironmentVariable(named = "AI_SERVICE_CONTRACT_URL", matches = ".+")
class AiServiceContractTest {
    @Test
    void 실제_FastAPI와_임베딩_주간회고_계약이_일치한다() {
        var client = new AiServiceClient(
                new AiServiceProperties(true, System.getenv("AI_SERVICE_CONTRACT_URL"),
                        "contract-test-token-12345678901234567890", Duration.ofSeconds(95), false, System.getenv("AI_SERVICE_CA_CERTIFICATE_BASE64")),
                new LlmProperties("https://unused.invalid", "contract-chat", "", Duration.ofSeconds(30),
                        null, 800, true, "low", 2, Duration.ofSeconds(1), "contract-embedding", 2));
        var untrusted = new AiServiceClient(
                new AiServiceProperties(true, System.getenv("AI_SERVICE_CONTRACT_URL"),
                        "contract-test-token-12345678901234567890", Duration.ofSeconds(95), false),
                new LlmProperties("https://unused.invalid", "contract-chat", "", Duration.ofSeconds(30),
                        null, 800, true, "low", 2, Duration.ofSeconds(1), "contract-embedding", 2));
        assertThatThrownBy(() -> untrusted.embed(List.of("untrusted CA"))).isInstanceOf(LlmException.class);
        var wrongHost = new AiServiceClient(
                new AiServiceProperties(true, System.getenv("AI_SERVICE_CONTRACT_URL").replace("127.0.0.1", "localhost"),
                        "contract-test-token-12345678901234567890", Duration.ofSeconds(95), false,
                        System.getenv("AI_SERVICE_CA_CERTIFICATE_BASE64")),
                new LlmProperties("https://unused.invalid", "contract-chat", "", Duration.ofSeconds(30),
                        null, 800, true, "low", 2, Duration.ofSeconds(1), "contract-embedding", 2));
        assertThatThrownBy(() -> wrongHost.embed(List.of("wrong hostname"))).isInstanceOf(LlmException.class);
        List<float[]> vectors = client.embed(List.of("운동", "독서"));
        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(1, 0);
        assertThat(vectors.get(1)).containsExactly(0, 1);
        assertThat(client.complete(LlmChatRequest.of("한국어와 영어 JSON", "Weekly routine stats")))
                .isEqualTo("{\"summary\":\"Contract verified\"}");
    }
}
