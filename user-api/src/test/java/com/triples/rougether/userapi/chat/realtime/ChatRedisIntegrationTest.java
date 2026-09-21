package com.triples.rougether.userapi.chat.realtime;

import static org.mockito.Mockito.*;

import com.triples.rougether.userapi.chat.service.ChatRoomChanged;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class ChatRedisIntegrationTest {
    @Test
    void 별도_Redis_연결을_쓰는_두_노드가_같은_채팅방_알림을_받는다() throws Exception {
        try (var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                .withExposedPorts(6379).withStartupTimeout(Duration.ofSeconds(60))) {
            redis.start();
            var config = new ChatRedisConfig();
            var nodeA = mock(ChatSocketHandler.class);
            var nodeB = mock(ChatSocketHandler.class);
            var factoryA = config.chatConnectionFactory(redis.getHost(), redis.getMappedPort(6379), "", false);
            var factoryB = config.chatConnectionFactory(redis.getHost(), redis.getMappedPort(6379), "", false);
            factoryA.afterPropertiesSet(); factoryA.start();
            factoryB.afterPropertiesSet(); factoryB.start();
            var listenerA = config.chatListener(factoryA, nodeA);
            var listenerB = config.chatListener(factoryB, nodeB);
            try {
                listenerA.afterPropertiesSet(); listenerA.start();
                listenerB.afterPropertiesSet(); listenerB.start();
                var beans = new StaticListableBeanFactory();
                beans.addBean("chatRedis", config.chatRedis(factoryA));
                new ChatChangePublisher(nodeA, beans.getBeanProvider(org.springframework.data.redis.core.StringRedisTemplate.class))
                        .committed(new ChatRoomChanged(42L));
                verify(nodeA, timeout(5000).atLeastOnce()).changed(42L);
                verify(nodeB, timeout(5000)).changed(42L);
            } finally {
                listenerA.destroy(); listenerB.destroy();
                factoryA.destroy(); factoryB.destroy();
            }
        }
    }
}
