package com.triples.rougether.userapi.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.chat.entity.ChatReactionType;
import com.triples.rougether.userapi.chat.dto.ChatReactionResponse;
import com.triples.rougether.userapi.chat.service.ChatReactionChanged;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.CloseStatus;
import tools.jackson.databind.JsonNode;

// 공감 프레임(MESSAGE_REACTIONS)의 옵트인·순서·안전장치. DB 조회는 ChatBodyPushTest의 stub을 씀.
class ChatReactionPushTest {
    static final List<ChatReactionResponse> HEART = List.of(new ChatReactionResponse(ChatReactionType.HEART, List.of(7L, 8L)));

    @Test
    void 옵트인한_연결만_공감_프레임을_받고_READY로_지원을_확인한다() throws Exception {
        try (var f = new ChatBodyPushTest.Fixture()) {
            var optedIn = f.connect("reactions", false, true);
            var both = f.connect("both", true, true);
            var legacy = f.connect("legacy", false, false);
            var bodyOnly = f.connect("body-only", true, false);
            JsonNode ready = optedIn.await("READY");
            assertThat(ready.path("includeReactions").asBoolean()).isTrue();
            assertThat(ready.has("includeMessages")).isFalse();
            JsonNode bothReady = both.await("READY");
            assertThat(bothReady.path("includeReactions").asBoolean()).isTrue();
            assertThat(bothReady.path("includeMessages").asBoolean()).isTrue();
            assertThat(legacy.await("READY").has("includeReactions")).isFalse();
            JsonNode bodyReady = bodyOnly.await("READY");
            assertThat(bodyReady.path("includeMessages").asBoolean()).isTrue();
            assertThat(bodyReady.has("includeReactions")).isFalse();
            f.append(1);
            f.handler.changed(3L);
            for (var socket : List.of(optedIn, both, legacy, bodyOnly)) socket.await("ROOM_UPDATED");
            f.awaitOutboundIdle();

            f.react(1, HEART);
            f.handler.reactionsChanged(3L, 1L);

            for (var socket : List.of(optedIn, both)) {
                JsonNode frame = socket.await("MESSAGE_REACTIONS");
                assertThat(frame.path("roomId").asLong()).isEqualTo(3);
                assertThat(frame.path("messageId").asLong()).isEqualTo(1);
                assertThat(frame.path("sequence").asLong()).isEqualTo(1);
                assertThat(frame.at("/reactions/0/type").asString()).isEqualTo("HEART");
                assertThat(frame.at("/reactions/0/userIds/1").asLong()).isEqualTo(8);
            }
            f.awaitOutboundIdle();
            assertThat(legacy.types()).containsExactly("READY", "ROOM_UPDATED");
            assertThat(bodyOnly.types()).containsExactly("READY", "MESSAGE_CREATED", "ROOM_UPDATED");
            // 공감만 바뀌면 방 상태를 다시 읽거나 ROOM_UPDATED를 보내지 않음.
            assertThat(optedIn.types()).containsExactly("READY", "ROOM_UPDATED", "MESSAGE_REACTIONS");
            assertThat(both.types()).containsExactly("READY", "MESSAGE_CREATED", "ROOM_UPDATED", "MESSAGE_REACTIONS");
        }
    }

    @Test
    void 다른_방의_공감과_그사이_삭제된_메시지의_공감은_보내지_않는다() throws Exception {
        try (var f = new ChatBodyPushTest.Fixture()) {
            var socket = f.connect("isolation", false, true);
            socket.await("READY");
            f.react(1, HEART);

            f.handler.reactionsChanged(99L, 1L);
            // 메시지 2는 조회 결과가 없음(삭제됨).
            f.handler.reactionsChanged(3L, 2L);
            f.awaitOutboundIdle();
            assertThat(socket.types()).containsExactly("READY");

            f.handler.reactionsChanged(3L, 1L);
            assertThat(socket.await("MESSAGE_REACTIONS").path("messageId").asLong()).isEqualTo(1);
        }
    }

    @Test
    void 전송_중_겹친_신호는_합쳐지고_마지막_프레임은_최신_값이다() throws Exception {
        try (var f = new ChatBodyPushTest.Fixture()) {
            var socket = f.connect("coalesce", false, true);
            socket.await("READY");
            f.awaitOutboundIdle();
            socket.block = event -> event.path("type").asString().equals("MESSAGE_REACTIONS");
            f.react(1, HEART);
            f.handler.reactionsChanged(3L, 1L);
            assertThat(socket.entered.await(2, TimeUnit.SECONDS)).isTrue();
            // 첫 프레임을 쓰는 동안 취소와 재설정이 이어서 커밋됨.
            f.react(1, List.of());
            f.handler.reactionsChanged(3L, 1L);
            var latest = List.of(new ChatReactionResponse(ChatReactionType.SAD, List.of(9L)));
            f.react(1, latest);
            f.handler.reactionsChanged(3L, 1L);
            socket.block = event -> false;
            socket.release.countDown();

            socket.await(event -> event.at("/reactions/0/type").asString().equals("SAD"));
            f.awaitOutboundIdle();
            var frames = socket.received.stream().filter(e -> e.path("type").asString().equals("MESSAGE_REACTIONS")).toList();
            // 겹친 신호 두 개는 프레임 하나로 합쳐짐.
            assertThat(frames).hasSize(2);
            assertThat(frames.getLast().at("/reactions/0/userIds/0").asLong()).isEqualTo(9);
            assertThat(socket.maxConcurrentWrites.get()).isEqualTo(1);
        }
    }

    @Test
    void 본문을_직접_받는_연결이_아직_받지_못한_메시지의_공감은_본문에_맡긴다() throws Exception {
        try (var f = new ChatBodyPushTest.Fixture()) {
            var socket = f.connect("body-first", true, true);
            socket.await("READY");
            f.awaitOutboundIdle();
            f.append(1);
            f.react(1, HEART);

            // 메시지 신호보다 공감 신호가 먼저 도착함(다른 노드에서 온 경우).
            f.handler.reactionsChanged(3L, 1L);
            f.awaitOutboundIdle();
            assertThat(socket.types()).containsExactly("READY");

            f.handler.changed(3L);
            socket.await("ROOM_UPDATED");
            f.handler.reactionsChanged(3L, 1L);
            socket.await("MESSAGE_REACTIONS");
            assertThat(socket.types()).containsExactly("READY", "MESSAGE_CREATED", "ROOM_UPDATED", "MESSAGE_REACTIONS");
        }
    }

    @Test
    void 공감_프레임도_쓰기_직전에_권한을_다시_검사한다() throws Exception {
        try (var f = new ChatBodyPushTest.Fixture()) {
            var socket = f.connect("revoked", false, true);
            socket.await("READY");
            f.awaitOutboundIdle();
            f.react(1, HEART);
            f.forbidden.set(true);

            f.handler.reactionsChanged(3L, 1L);

            assertThat(socket.closed.get(2, TimeUnit.SECONDS)).isEqualTo(CloseStatus.POLICY_VIOLATION);
            assertThat(socket.types()).containsExactly("READY");
        }
    }

    @Test
    void 보내지_못한_공감_변경이_한도를_넘으면_연결을_끊어_재접속으로_복구하게_한다() throws Exception {
        try (var f = new ChatBodyPushTest.Fixture()) {
            var socket = f.connect("overflow", false, true);
            socket.await("READY");
            f.awaitOutboundIdle();
            socket.block = event -> event.path("type").asString().equals("MESSAGE_REACTIONS");
            f.react(1, HEART);
            f.handler.reactionsChanged(3L, 1L);
            assertThat(socket.entered.await(2, TimeUnit.SECONDS)).isTrue();

            // 쓰기가 막힌 동안 서로 다른 메시지 257개의 공감이 바뀜.
            for (long messageId = 2; messageId <= 258; messageId++) f.handler.reactionsChanged(3L, messageId);

            assertThat(socket.closed.get(2, TimeUnit.SECONDS)).isEqualTo(CloseStatus.SERVICE_OVERLOAD);
        }
    }

    @Test
    void 공감_변경은_전용_Redis_채널로_알리고_발행_실패가_저장을_뒤집지_않는다() {
        var sockets = mock(ChatSocketHandler.class);
        var redis = mock(StringRedisTemplate.class);
        var factory = new StaticListableBeanFactory();
        factory.addBean("chatRedis", redis);
        var publisher = new ChatChangePublisher(sockets, factory.getBeanProvider(StringRedisTemplate.class));

        publisher.reactionCommitted(new ChatReactionChanged(3L, 120L));

        verify(sockets).reactionsChanged(3L, 120L);
        verify(redis).convertAndSend("rougether:chat:reactions-changed:v1", "3:120");
        // 방 ID만 싣는 기존 채널에는 아무것도 보내지 않음.
        verify(redis, never()).convertAndSend(org.mockito.ArgumentMatchers.eq("rougether:chat:room-changed:v1"), any());
        verify(sockets, never()).changed(anyLong());

        when(redis.convertAndSend(org.mockito.ArgumentMatchers.anyString(), any())).thenThrow(new IllegalStateException("offline"));
        org.assertj.core.api.Assertions.assertThatCode(() -> publisher.reactionCommitted(new ChatReactionChanged(3L, 121L)))
                .doesNotThrowAnyException();
        verify(sockets).reactionsChanged(3L, 121L);
    }

    @Test
    void 공감_수신을_켜지_않은_연결은_공감_조회를_하지_않는다() throws Exception {
        try (var f = new ChatBodyPushTest.Fixture()) {
            var socket = f.connect("legacy", true, false);
            socket.await("READY");
            f.react(1, HEART);
            f.handler.reactionsChanged(3L, 1L);
            f.awaitOutboundIdle();
            assertThat(socket.types()).containsExactly("READY");
            verify(f.query, never()).liveReactions(anyLong(), anyLong());
            verify(f.query, never()).messages(anyLong(), anyLong(), any(), any(), anyInt());
        }
    }
}
