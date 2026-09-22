package com.triples.rougether.batch.weeklyreport;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.infra.llm.AiServiceProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;

class AiServiceEnvironmentTest {
    @Test
    void 문서의_환경변수로_AI_서비스_전환설정을_주입한다() throws IOException {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("deployment", Map.of(
                "AI_SERVICE_ENABLED", "true", "AI_SERVICE_BASE_URL", "http://ai.internal:8090",
                "AI_SERVICE_TOKEN", "test-internal-token-12345678901234567890",
                "AI_SERVICE_TIMEOUT", "100s", "AI_SERVICE_ALLOW_INSECURE_HTTP", "true")));
        new YamlPropertySourceLoader().load("application.yml", new FileSystemResource("src/main/resources/application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        var properties = Binder.get(environment).bind("ai.service", Bindable.of(AiServiceProperties.class))
                .orElseThrow(() -> new IllegalStateException("AI 설정 바인딩 실패"));
        assertThat(properties.enabled()).isTrue();
        assertThat(properties.baseUrl()).isEqualTo("http://ai.internal:8090");
        assertThat(properties.allowInsecureHttp()).isTrue();
        assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(100));
    }
}
