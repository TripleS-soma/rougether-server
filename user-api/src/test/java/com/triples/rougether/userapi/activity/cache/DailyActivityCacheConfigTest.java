package com.triples.rougether.userapi.activity.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslVerifyMode;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(OutputCaptureExtension.class)
class DailyActivityCacheConfigTest {
    private static final String TEST_PASSWORD = "activity-config-test-only-token";
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DailyActivityCacheConfig.class);

    @Test
    void 기본값과_명시적_비활성화는_활동_Redis_빈을_만들지_않는다() {
        for (String enabled : new String[] {"", "false"}) {
            var runner = enabled.isEmpty() ? contextRunner
                    : contextRunner.withPropertyValues("activity.shared-cache.enabled=" + enabled);
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean("dailyActivityConnectionFactory");
                assertThat(context).doesNotHaveBean("dailyActivityRedis");
            });
        }
    }

    @Test
    void 활성화만_하면_기존_로컬_무인증_연결과_장애_제한을_유지한다() {
        contextRunner.withPropertyValues("activity.shared-cache.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            var factory = context.getBean("dailyActivityConnectionFactory", LettuceConnectionFactory.class);
            assertThat(factory.getHostName()).isEqualTo("localhost");
            assertThat(factory.getPort()).isEqualTo(6379);
            assertThat(factory.getStandaloneConfiguration().getPassword().isPresent()).isFalse();
            assertThat(factory.getClientConfiguration().isUseSsl()).isFalse();
            assertFailureLimits(factory);
        });
    }

    @Test
    void TLS와_암호를_설정하면_인증서_검증과_기존_장애_제한을_유지한다() {
        authenticatedContext().run(context -> {
            assertThat(context).hasNotFailed();
            var factory = context.getBean("dailyActivityConnectionFactory", LettuceConnectionFactory.class);
            assertThat(factory.getHostName()).isEqualTo("activity-cache.example.test");
            assertThat(factory.getPort()).isEqualTo(6380);
            assertThat(factory.getStandaloneConfiguration().getPassword().get())
                    .containsExactly(TEST_PASSWORD.toCharArray());
            assertThat(factory.getClientConfiguration().isUseSsl()).isTrue();
            assertThat(factory.getClientConfiguration().getVerifyMode()).isEqualTo(SslVerifyMode.FULL);
            assertThat(factory.getClientConfiguration().isStartTls()).isFalse();
            assertFailureLimits(factory);
        });
    }

    @Test
    void 시작과_종료_로그나_설정_객체의_문자열에_암호를_노출하지_않는다(CapturedOutput output) {
        authenticatedContext().run(context -> {
            assertThat(context).hasNotFailed();
            var factory = context.getBean("dailyActivityConnectionFactory", LettuceConnectionFactory.class);
            assertThat(factory.toString()).doesNotContain(TEST_PASSWORD);
            assertThat(factory.getStandaloneConfiguration().toString()).doesNotContain(TEST_PASSWORD);
            assertThat(factory.getStandaloneConfiguration().getPassword().toString()).doesNotContain(TEST_PASSWORD);
        });
        assertThat(output.getAll()).doesNotContain(TEST_PASSWORD);
    }

    @Test
    void 다른_Redis_연결이_있어도_활동_템플릿은_전용_연결만_사용한다() {
        authenticatedContext()
                .withBean("otherRedisConnectionFactory", LettuceConnectionFactory.class,
                        () -> new LettuceConnectionFactory("other-cache.example.test", 6379))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var template = context.getBean("dailyActivityRedis", StringRedisTemplate.class);
                    assertThat(template.getConnectionFactory())
                            .isSameAs(context.getBean("dailyActivityConnectionFactory"))
                            .isNotSameAs(context.getBean("otherRedisConnectionFactory"));
                });
    }

    private ApplicationContextRunner authenticatedContext() {
        return contextRunner.withPropertyValues(
                "activity.shared-cache.enabled=true",
                "activity.shared-cache.host=activity-cache.example.test",
                "activity.shared-cache.port=6380",
                "activity.shared-cache.ssl-enabled=true",
                "activity.shared-cache.password=" + TEST_PASSWORD);
    }

    private void assertFailureLimits(LettuceConnectionFactory factory) {
        var client = factory.getClientConfiguration();
        assertThat(client.getCommandTimeout()).isEqualTo(Duration.ofMillis(100));
        assertThat(client.getShutdownTimeout()).isEqualTo(Duration.ZERO);
        var options = client.getClientOptions().orElseThrow();
        assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofMillis(200));
        assertThat(options.getRequestQueueSize()).isEqualTo(256);
        assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
        assertThat(factory.getEagerInitialization()).isFalse();
    }
}
