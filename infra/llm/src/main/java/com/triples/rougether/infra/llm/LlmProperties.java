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
// - embeddingModel/embeddingDimensions 는 {base-url}/embeddings 호출용(같은 키·타임아웃·재시도 공유). dimensions 는 양수일 때만
//   전송하며(OpenAI text-embedding-3 계열의 Matryoshka 축소), 미지원 공급자는 0(LLM_EMBEDDING_DIMENSIONS=0)으로 끈다.
//   (빈 문자열은 Binder 가 "값 없음"으로 보고 기본값을 적용하므로 끄는 값으로 쓸 수 없다. 타입을 Integer 로 둔 것은
//   빈 문자열이 int 바인딩 오류로 앱 기동을 막지 않게 하기 위함.)
//   기본 text-embedding-3-large/1024 는 한글 짧은 제목 쌍 30개 실측(#303) 결과 — small/512 는 유사 쌍 코사인이 전반적으로 낮아
//   무관 쌍과 겹쳤고(오분류 최소 4/30), large/1024 는 2/30 로 개선돼 채택. 비용은 요청당 여전히 수천분의 1센트 수준.
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
        @DefaultValue("1s") Duration retryBackoff,
        @DefaultValue("text-embedding-3-large") String embeddingModel,
        @DefaultValue("1024") Integer embeddingDimensions) {
}
