package com.triples.rougether.infra.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

class AiServiceConfigurationTest {
    @Test
    void 기본_모드에서는_기존_stub을_유지한다() {
        try (var context = context()) {
            assertThat(context.getBean(LlmClient.class)).isInstanceOf(StubLlmClient.class);
            assertThat(context.getBean(EmbeddingClient.class)).isInstanceOf(StubEmbeddingClient.class);
        }
    }

    @Test
    void 직접_모드에서는_기존_공급자_클라이언트를_유지한다() {
        try (var context = context("llm.api-key=provider-key")) {
            assertThat(context.getBean(LlmClient.class)).isInstanceOf(OpenAiCompatibleLlmClient.class);
            assertThat(context.getBean(EmbeddingClient.class)).isInstanceOf(OpenAiCompatibleEmbeddingClient.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "rollback-provider-key"})
    void 원격_모드는_공급자_키_유무와_관계없이_내부_클라이언트_한개만_사용한다(String providerKey) {
        try (var context = context("ai.service.enabled=true", "ai.service.base-url=http://127.0.0.1:8090",
                "ai.service.token=internal-test-token-12345678901234567890", "llm.api-key=" + providerKey)) {
            assertThat(context.getBeansOfType(LlmClient.class)).hasSize(1);
            assertThat(context.getBeansOfType(EmbeddingClient.class)).hasSize(1);
            assertThat(context.getBean(LlmClient.class)).isSameAs(context.getBean(EmbeddingClient.class));
            assertThat(context.getBean(LlmClient.class)).isInstanceOf(AiServiceClient.class);
            assertThat(context.getBean(LlmClient.class).isAvailable()).isTrue();
        }
    }

    @Test
    void 원격_설정이_잘못되면_stub으로_넘기지_않고_기동에_실패한다() {
        assertThatThrownBy(() -> context("ai.service.enabled=true", "ai.service.base-url=http://127.0.0.1:8090"))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 내부_HTTP는_명시적으로_허용하고_토큰을_설정_문자열에_노출하지_않는다() {
        String token = "internal-test-token-12345678901234567890";
        assertThatThrownBy(() -> new AiServiceProperties(true, "http://ai.internal:8090", token, Duration.ofSeconds(95), false))
                .isInstanceOf(IllegalArgumentException.class);
        var properties = new AiServiceProperties(true, "http://ai.internal:8090", token, Duration.ofSeconds(95), true);
        assertThat(properties.toString()).doesNotContain(token);
        assertThatThrownBy(() -> new AiServiceProperties(true, "https://ai.internal", token, Duration.ofSeconds(30), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AnnotationConfigApplicationContext context(String... properties) {
        var context = new AnnotationConfigApplicationContext();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, properties);
        context.register(LlmConfig.class, AiServiceClient.class, OpenAiCompatibleLlmClient.class,
                OpenAiCompatibleEmbeddingClient.class, StubLlmClient.class, StubEmbeddingClient.class);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException e) {
            context.close();
            throw e;
        }
    }
}
