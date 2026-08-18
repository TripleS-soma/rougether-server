package com.triples.rougether.infra.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

// llm.* 바인딩 — 임베딩 설정의 기본값과 "빈 값이면 dimensions 미전송(null)" 계약을 실제 Binder 로 확인
class LlmPropertiesBindingTest {

    private static LlmProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("llm", LlmProperties.class);
    }

    @Test
    void 임베딩_설정_기본값은_text_embedding_3_small_512() {
        LlmProperties properties = bind(Map.of());

        assertThat(properties.embeddingModel()).isEqualTo("text-embedding-3-small");
        assertThat(properties.embeddingDimensions()).isEqualTo(512);
    }

    @Test
    void embedding_dimensions는_0으로_끌_수_있고_빈_문자열은_기본값이_적용된다() {
        // env LLM_EMBEDDING_DIMENSIONS=0 → dimensions 미전송(미지원 공급자용). 빈 문자열은 Binder 가 "값 없음"으로 봐 512 가 된다
        LlmProperties off = bind(Map.of("llm.embedding-model", "nvidia/nv-embed-v1",
                "llm.embedding-dimensions", "0"));
        assertThat(off.embeddingModel()).isEqualTo("nvidia/nv-embed-v1");
        assertThat(off.embeddingDimensions()).isZero();

        assertThat(bind(Map.of("llm.embedding-dimensions", "")).embeddingDimensions()).isEqualTo(512);
    }
}
