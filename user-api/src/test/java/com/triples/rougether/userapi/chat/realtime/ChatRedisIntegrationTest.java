package com.triples.rougether.userapi.chat.realtime;

import static org.assertj.core.api.Assertions.*;

import com.triples.rougether.userapi.chat.service.ChatRoomChanged;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.socket.TextMessage;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class ChatRedisIntegrationTest {
    @Test
    void 별도_Redis_연결을_쓰는_두_노드가_같은_본문을_중복없이_전달한다() throws Exception {
        // DB 조회는 공유 fixture로 대체하고 실제 Redis 연결과 소켓 처리기를 두 개 사용함.
        try (var fixture = new ChatBodyPushTest.Fixture();
             var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                .withExposedPorts(6379).withStartupTimeout(Duration.ofSeconds(60))) {
            redis.start();
            var config = new ChatRedisConfig();
            var nodeA = fixture.handler;
            var nodeB = new ChatSocketHandler(fixture.query, fixture.token, fixture.mapper, fixture.clock);
            var factoryA = config.chatConnectionFactory(redis.getHost(), redis.getMappedPort(6379), "", false);
            var factoryB = config.chatConnectionFactory(redis.getHost(), redis.getMappedPort(6379), "", false);
            factoryA.afterPropertiesSet(); factoryA.start();
            factoryB.afterPropertiesSet(); factoryB.start();
            var listenerA = config.chatListener(factoryA, nodeA);
            var listenerB = config.chatListener(factoryB, nodeB);
            try {
                listenerA.afterPropertiesSet(); listenerA.start();
                listenerB.afterPropertiesSet(); listenerB.start();
                var socketA = fixture.connect("node-a", true);
                var socketB = fixture.socket("node-b");
                nodeB.afterConnectionEstablished(socketB.raw);
                nodeB.handleTextMessage(socketB.raw, new TextMessage(
                        "{\"type\":\"SUBSCRIBE\",\"roomId\":3,\"accessToken\":\"token\",\"includeMessages\":true}"));
                socketA.await("READY"); socketB.await("READY");
                var beans = new StaticListableBeanFactory();
                beans.addBean("chatRedis", config.chatRedis(factoryA));
                var publisher = new ChatChangePublisher(nodeA,
                        beans.getBeanProvider(org.springframework.data.redis.core.StringRedisTemplate.class));
                fixture.append(1);
                publisher.committed(new ChatRoomChanged(3L));
                assertThat(socketA.await("MESSAGE_CREATED").path("message").path("content").asString()).isEqualTo("본문 1");
                assertThat(socketB.await("MESSAGE_CREATED").path("message").path("content").asString()).isEqualTo("본문 1");
                publisher.committed(new ChatRoomChanged(3L));
                fixture.append(2);
                publisher.committed(new ChatRoomChanged(3L));
                assertThat(socketA.await("MESSAGE_CREATED").path("message").path("sequence").asLong()).isEqualTo(2);
                assertThat(socketB.await("MESSAGE_CREATED").path("message").path("sequence").asLong()).isEqualTo(2);
                assertThat(socketA.sequences()).containsExactly(1L, 2L);
                assertThat(socketB.sequences()).containsExactly(1L, 2L);
            } finally {
                listenerA.destroy(); listenerB.destroy();
                nodeB.shutdown();
                factoryA.destroy(); factoryB.destroy();
            }
        }
    }
}
