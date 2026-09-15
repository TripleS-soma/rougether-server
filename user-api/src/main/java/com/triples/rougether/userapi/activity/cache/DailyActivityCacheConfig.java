package com.triples.rougether.userapi.activity.cache;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "activity.shared-cache.enabled", havingValue = "true")
public class DailyActivityCacheConfig {

    @Bean
    LettuceConnectionFactory dailyActivityConnectionFactory(
            @Value("${activity.shared-cache.host:localhost}") String host,
            @Value("${activity.shared-cache.port:6379}") int port,
            @Value("${activity.shared-cache.ssl-enabled:false}") boolean sslEnabled,
            @Value("${activity.shared-cache.password:}") String password) {
        var server = new RedisStandaloneConfiguration(host, port);
        server.setPassword(RedisPassword.of(password));
        var options = ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(200)).build())
                .requestQueueSize(256)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build();
        var client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(100))
                .shutdownTimeout(Duration.ZERO)
                .clientOptions(options);
        if (sslEnabled) {
            client.useSsl();
        }
        return new LettuceConnectionFactory(server, client.build());
    }

    @Bean
    StringRedisTemplate dailyActivityRedis(
            @Qualifier("dailyActivityConnectionFactory") LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
