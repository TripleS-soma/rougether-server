package com.triples.rougether.userapi.chat.realtime;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.nio.charset.StandardCharsets;
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
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "chat.redis.enabled", havingValue = "true")
public class ChatRedisConfig {
    static final String CHANNEL = "rougether:chat:room-changed:v1";
    // 공감 변경 전용. 내용은 "방 ID:메시지 ID"이고 구버전 노드는 이 채널을 구독하지 않음.
    static final String REACTION_CHANNEL = "rougether:chat:reactions-changed:v1";

    @Bean
    LettuceConnectionFactory chatConnectionFactory(
            @Value("${chat.redis.host:localhost}") String host,
            @Value("${chat.redis.port:6379}") int port,
            @Value("${chat.redis.password:}") String password,
            @Value("${chat.redis.ssl-enabled:false}") boolean ssl) {
        var server = new RedisStandaloneConfiguration(host, port);
        server.setPassword(RedisPassword.of(password));
        var options = ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(300)).build())
                .requestQueueSize(256).disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build();
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200))
                .shutdownTimeout(Duration.ZERO).clientOptions(options);
        if (ssl) client.useSsl();
        return new LettuceConnectionFactory(server, client.build());
    }

    @Bean
    StringRedisTemplate chatRedis(@Qualifier("chatConnectionFactory") LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    RedisMessageListenerContainer chatListener(@Qualifier("chatConnectionFactory") LettuceConnectionFactory factory,
                                               ChatSocketHandler sockets) {
        var listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(factory);
        listener.addMessageListener((message, pattern) -> {
            try {
                sockets.changed(Long.parseLong(new String(message.getBody(), StandardCharsets.UTF_8)));
            } catch (NumberFormatException ignored) { }
        }, new ChannelTopic(CHANNEL));
        listener.addMessageListener((message, pattern) -> {
            try {
                String[] ids = new String(message.getBody(), StandardCharsets.UTF_8).split(":", -1);
                if (ids.length == 2) sockets.reactionsChanged(Long.parseLong(ids[0]), Long.parseLong(ids[1]));
            } catch (NumberFormatException ignored) { }
        }, new ChannelTopic(REACTION_CHANNEL));
        return listener;
    }
}
