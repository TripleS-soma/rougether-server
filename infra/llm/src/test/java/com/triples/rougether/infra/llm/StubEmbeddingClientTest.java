package com.triples.rougether.infra.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class StubEmbeddingClientTest {

    private final StubEmbeddingClient stub = new StubEmbeddingClient();

    @Test
    void isAvailable은_false이고_embed는_non_retryable_LlmException() {
        assertThat(stub.isAvailable()).isFalse();
        assertThatThrownBy(() -> stub.embed(List.of("a")))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).isRetryable()).isFalse());
    }
}
