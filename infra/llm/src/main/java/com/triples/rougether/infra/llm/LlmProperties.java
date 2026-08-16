package com.triples.rougether.infra.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// OpenAI 호환 chat/completions 엔드포인트 설정. 기본값은 OpenAI GPT-5.6 저지연 변종(gpt-5.6-luna, reasoning low)이며,
// base-url·model 만 바꾸면 같은 스키마를 쓰는 다른 공급자로 교체할 수 있다.
// - GPT-5 계열은 max_tokens 대신 max_completion_tokens 를 쓰고 temperature 를 거부하므로 temperature 는 null(미전송)이 기본.
//   temperature 를 받는 모델(gpt-4.1 등)로 바꿀 때만 값을 준다.
// - reasoningEffort(none/low/medium/high…)는 값이 있을 때만 보낸다(미지원 공급자는 비워 둔다).
// - jsonMode 는 response_format=json_object 전송 여부(OpenAI 지원, 일부 호환 API는 미지원이라 끌 수 있게 둠).
// apiKey 는 환경변수(LLM_API_KEY) 주입, 커밋 금지 — 비어 있으면 StubLlmClient 가 대신 활성화된다.
@ConfigurationProperties("llm")
public record LlmProperties(
        @DefaultValue("https://api.openai.com/v1") String baseUrl,
        @DefaultValue("gpt-5.6-luna") String model,
        @DefaultValue("") String apiKey,
        @DefaultValue("30s") Duration timeout,
        Double temperature,
        @DefaultValue("800") int maxTokens,
        @DefaultValue("true") boolean jsonMode,
        @DefaultValue("low") String reasoningEffort,
        @DefaultValue("2") int maxRetries,
        @DefaultValue("1s") Duration retryBackoff) {
}
