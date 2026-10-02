package com.triples.rougether.userapi.chat.realtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.triples.rougether.common.error.AuthErrorCode;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.chat.entity.ChatRoomType;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.chat.dto.*;
import com.triples.rougether.userapi.chat.error.ChatErrorCode;
import com.triples.rougether.userapi.chat.service.ChatQueryService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.MemberRole;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ChatBodyPushTest {
    @Test
    void 스케줄러_호출_없이_커밋_신호로_본문을_먼저_보내고_상태를_알린다() throws Exception {
        try (var f = new Fixture()) {
            var socket = f.connect("direct", true);
            assertThat(socket.await("READY").path("includeMessages").asBoolean()).isTrue();
            f.append(1);
            f.handler.changed(3L);
            JsonNode event = socket.await("MESSAGE_CREATED");
            assertThat(event.path("message").path("content").asString()).isEqualTo("본문 1");
            assertThat(event.path("message").path("clientMessageId").asString()).isEqualTo(f.messages.getFirst().clientMessageId());
            assertThat(socket.await("ROOM_UPDATED").path("room").path("lastSequence").asLong()).isEqualTo(1);
            assertThat(socket.types()).containsExactly("READY", "MESSAGE_CREATED", "ROOM_UPDATED");
        }
    }

    @Test
    void 기존_구독은_본문_없이_동일한_상태_프레임만_받는다() throws Exception {
        try (var f = new Fixture()) {
            var socket = f.connect("legacy", false);
            assertThat(socket.await("READY").has("includeMessages")).isFalse();
            f.append(1);
            f.handler.changed(3L);
            socket.await("ROOM_UPDATED");
            assertThat(socket.types()).containsExactly("READY", "ROOM_UPDATED");
            verify(f.query, never()).messages(anyLong(), anyLong(), any(), any(), anyInt());
        }
    }

    @Test
    void READY_전송_중_발생한_메시지도_READY_다음에_전달된다() throws Exception {
        try (var f = new Fixture()) {
            var socket = f.socket("subscribe-race");
            socket.block = event -> event.path("type").asString().equals("READY");
            f.subscribe(socket, true);
            assertThat(socket.entered.await(2, TimeUnit.SECONDS)).isTrue();
            f.append(1);
            f.handler.changed(3L);
            socket.release.countDown();
            socket.await("MESSAGE_CREATED");
            assertThat(socket.types().subList(0, 2)).containsExactly("READY", "MESSAGE_CREATED");
        }
    }

    @Test
    void 전송_중_도착한_중복_역순_신호에도_DB_순서대로_누락없이_이어_보낸다() throws Exception {
        try (var f = new Fixture()) {
            var socket = f.connect("busy", true);
            socket.await("READY");
            socket.block = event -> event.path("message").path("sequence").asLong() == 1;
            f.append(1);
            f.handler.changed(3L);
            assertThat(socket.entered.await(2, TimeUnit.SECONDS)).isTrue();
            f.append(2);
            f.append(3);
            f.handler.changed(3L);
            f.handler.changed(3L);
            socket.release.countDown();
            socket.await(event -> event.path("room").path("lastSequence").asLong() == 3);
            assertThat(socket.sequences()).containsExactly(1L, 2L, 3L);
            assertThat(socket.maxConcurrentWrites.get()).isEqualTo(1);
        }
    }

    @Test
    void 한_페이지를_넘는_본문도_추가_신호_없이_끝까지_전달한다() throws Exception {
        try (var f = new Fixture()) {
            var socket = f.connect("pages", true);
            socket.await("READY");
            for (int i = 1; i <= 105; i++) f.append(i);
            f.handler.changed(3L);
            socket.await("ROOM_UPDATED");
            assertThat(socket.sequences()).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 105).boxed().toList());
            verify(f.query).messages(1L, 3L, null, 0L, 50);
            verify(f.query).messages(1L, 3L, null, 50L, 50);
            verify(f.query).messages(1L, 3L, null, 100L, 50);
        }
    }

    @Test
    void Redis_신호가_유실되어도_주기_복구가_본문을_전달한다() throws Exception {
        try (var f = new Fixture()) {
            var socket = f.connect("reconcile", true);
            socket.await("READY");
            f.append(1);
            f.now.set(5000);
            f.handler.flush();
            assertThat(socket.await("MESSAGE_CREATED").path("message").path("sequence").asLong()).isEqualTo(1);
        }
    }

    @Test
    void 구독_전_기록은_READY_기준으로_구분하고_새_메시지만_본문으로_보낸다() throws Exception {
        try (var f = new Fixture()) {
            f.append(1);
            var socket = f.connect("history", true);
            assertThat(socket.await("READY").path("room").path("lastSequence").asLong()).isEqualTo(1);
            f.append(2);
            f.handler.changed(3L);
            socket.await("ROOM_UPDATED");
            assertThat(socket.sequences()).containsExactly(2L);
        }
    }

    @Test
    void 다른_방의_변경은_구독자에게_전달하지_않는다() throws Exception {
        try (var f = new Fixture()) {
            var socket = f.connect("isolation", true);
            socket.await("READY");
            f.append(1);
            f.handler.changed(99L);
            assertThat(socket.events.poll(100, TimeUnit.MILLISECONDS)).isNull();
            verify(f.query, never()).messages(anyLong(), anyLong(), any(), any(), anyInt());
        }
    }

    @Test
    void 전송_중_강퇴되면_이미_조회한_다음_본문도_보내지_않는다() throws Exception {
        blockedThenRevoked(false);
    }

    @Test
    void 전송_중_토큰이_만료되면_다음_본문을_보내지_않는다() throws Exception {
        blockedThenRevoked(true);
    }

    private void blockedThenRevoked(boolean expireToken) throws Exception {
        try (var f = new Fixture()) {
            var socket = f.connect("revoked", true);
            socket.await("READY");
            socket.block = event -> event.path("message").path("sequence").asLong() == 1;
            f.append(1);
            f.append(2);
            f.handler.changed(3L);
            assertThat(socket.entered.await(2, TimeUnit.SECONDS)).isTrue();
            if (expireToken) f.expired.set(true);
            else f.forbidden.set(true);
            socket.release.countDown();
            assertThat(socket.closed.get(2, TimeUnit.SECONDS)).isEqualTo(CloseStatus.POLICY_VIOLATION);
            assertThat(socket.sequences()).containsExactly(1L);
        }
    }

    @Test
    void 막힌_소켓은_감시_주기에_종료하고_나머지_본문을_버퍼에_쌓지_않는다() throws Exception {
        try (var f = new Fixture()) {
            var socket = f.connect("timeout", true);
            socket.await("READY");
            socket.block = event -> event.path("type").asString().equals("MESSAGE_CREATED");
            f.append(1);
            f.handler.changed(3L);
            assertThat(socket.entered.await(2, TimeUnit.SECONDS)).isTrue();
            f.append(2);
            f.handler.changed(3L);
            f.now.set(3000);
            f.handler.flush();
            assertThat(socket.closed.get(2, TimeUnit.SECONDS)).isEqualTo(CloseStatus.SESSION_NOT_RELIABLE);
            assertThat(socket.sequences()).isEmpty();
        }
    }

    @Test
    void 송신_작업_대기열이_차면_연결을_종료해_재접속_복구하도록_한다() throws Exception {
        try (var f = new Fixture()) {
            var executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(f.handler, "outbound");
            var started = new CountDownLatch(4);
            var release = new CountDownLatch(1);
            try {
                for (int i = 0; i < 4; i++) executor.execute(() -> {
                    started.countDown();
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                for (int i = 0; i < 256; i++) executor.execute(() -> {});
                var socket = f.connect("overload", true);
                assertThat(socket.closed.get(2, TimeUnit.SECONDS)).isEqualTo(CloseStatus.SERVICE_OVERLOAD);
                assertThat(socket.received).isEmpty();
            } finally { release.countDown(); }
        }
    }

    @Test
    void DB_순서에_틈이_있으면_커서를_건너뛰지_않고_연결을_종료한다() throws Exception {
        try (var f = new Fixture()) {
            var socket = f.connect("gap", true);
            socket.await("READY");
            f.append(2);
            f.handler.changed(3L);
            assertThat(socket.closed.get(2, TimeUnit.SECONDS)).isEqualTo(CloseStatus.SESSION_NOT_RELIABLE);
            assertThat(socket.sequences()).isEmpty();
        }
    }

    @Test
    void 연결이_닫힌_후에는_대기하던_작업이_본문을_보내지_않는다() throws Exception {
        try (var f = new Fixture()) {
            var socket = f.connect("closed", true);
            socket.await("READY");
            f.handler.afterConnectionClosed(socket.raw, CloseStatus.NORMAL);
            f.append(1);
            f.handler.changed(3L);
            f.handler.flush();
            assertThat(socket.events.poll(100, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    static class Fixture implements AutoCloseable {
        final JsonMapper mapper = JsonMapper.builder().build();
        final ChatQueryService query = mock(ChatQueryService.class);
        final TokenService token = mock(TokenService.class);
        final Clock clock = mock(Clock.class);
        final AtomicLong now = new AtomicLong();
        final AtomicBoolean forbidden = new AtomicBoolean();
        final AtomicBoolean expired = new AtomicBoolean();
        final List<ChatMessageResponse> messages = new CopyOnWriteArrayList<>();
        final ChatSocketHandler handler = new ChatSocketHandler(query, token, mapper, clock);

        Fixture() {
            when(clock.millis()).thenAnswer(i -> now.get());
            when(token.parseAccessToken("token")).thenAnswer(i -> {
                if (expired.get()) throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
                return new AuthUser(1L, MemberRole.NORMAL);
            });
            when(query.get(1L, 3L)).thenAnswer(i -> { authorize(); return state(); });
            doAnswer(i -> { authorize(); return null; }).when(query).requireReadable(1L, 3L);
            when(query.messages(eq(1L), eq(3L), isNull(), anyLong(), eq(50))).thenAnswer(i -> {
                authorize();
                long after = i.getArgument(3);
                var found = messages.stream().filter(m -> m.sequence() > after).toList();
                boolean more = found.size() > 50;
                var page = found.subList(0, Math.min(found.size(), 50));
                return new ChatMessageListResponse(page, more ? page.getLast().sequence() : null, more, state());
            });
        }
        void authorize() {
            if (forbidden.get()) throw new BusinessException(ChatErrorCode.CHAT_FORBIDDEN);
        }
        ChatRoomResponse state() {
            return new ChatRoomResponse(3L, ChatRoomType.HOUSE, 2L,
                    messages.isEmpty() ? 0 : messages.getLast().sequence(), List.of(new ChatRoomResponse.Reader(1L, 11L, 0)));
        }
        void append(long sequence) {
            messages.add(new ChatMessageResponse(sequence, 3L, sequence, UUID.randomUUID().toString(), 1L,
                    "회원", null, "본문 " + sequence, Instant.parse("2026-10-02T00:00:00Z"), 0));
        }
        Socket socket(String id) throws Exception { return new Socket(id, mapper); }
        Socket connect(String id, boolean include) throws Exception {
            var socket = socket(id);
            subscribe(socket, include);
            return socket;
        }
        void subscribe(Socket socket, boolean include) throws Exception {
            handler.afterConnectionEstablished(socket.raw);
            handler.handleTextMessage(socket.raw, new TextMessage("{\"type\":\"SUBSCRIBE\",\"roomId\":3,\"accessToken\":\"token\""
                    + (include ? ",\"includeMessages\":true" : "") + "}"));
        }
        public void close() { handler.shutdown(); }
    }

    static class Socket {
        final WebSocketSession raw = mock(WebSocketSession.class);
        final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        final List<JsonNode> received = new CopyOnWriteArrayList<>();
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger writing = new AtomicInteger();
        final AtomicInteger maxConcurrentWrites = new AtomicInteger();
        final AtomicBoolean open = new AtomicBoolean(true);
        volatile Predicate<JsonNode> block = event -> false;
        Socket(String id, JsonMapper mapper) throws Exception {
            when(raw.getId()).thenReturn(id);
            when(raw.isOpen()).thenAnswer(i -> open.get());
            doAnswer(i -> {
                int writes = writing.incrementAndGet();
                maxConcurrentWrites.accumulateAndGet(writes, Math::max);
                try {
                    JsonNode event = mapper.readTree(((TextMessage) i.getArgument(0)).getPayload());
                    if (block.test(event)) {
                        entered.countDown();
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    }
                    if (open.get()) { received.add(event); events.add(event); }
                    return null;
                } finally { writing.decrementAndGet(); }
            }).when(raw).sendMessage(any());
            doAnswer(i -> {
                open.set(false);
                release.countDown();
                closed.complete(i.getArgument(0));
                return null;
            }).when(raw).close(any());
        }
        JsonNode await(String type) throws Exception { return await(e -> e.path("type").asString().equals(type)); }
        JsonNode await(Predicate<JsonNode> test) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                var event = events.poll(100, TimeUnit.MILLISECONDS);
                if (event != null && test.test(event)) return event;
            }
            throw new AssertionError("기대한 소켓 프레임이 오지 않음: " + received);
        }
        List<String> types() { return received.stream().map(e -> e.path("type").asString()).toList(); }
        List<Long> sequences() {
            return received.stream().filter(e -> e.path("type").asString().equals("MESSAGE_CREATED"))
                    .map(e -> e.path("message").path("sequence").asLong()).toList();
        }
    }
}
