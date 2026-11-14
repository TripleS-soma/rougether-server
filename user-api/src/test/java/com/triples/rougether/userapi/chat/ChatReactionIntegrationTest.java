package com.triples.rougether.userapi.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.chat.entity.ChatReactionType;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.userapi.chat.dto.ChatMessageResponse;
import com.triples.rougether.userapi.chat.dto.ChatReactionResponse;
import com.triples.rougether.userapi.global.security.MemberRole;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

// 메시지 공감 설정·교체·취소와 응답 집계, 실제 WebSocket 전달.
class ChatReactionIntegrationTest extends ChatRichTestSupport {

    String path(Fixture f, Long messageId) {
        return "/api/v1/chat/rooms/" + f.room() + "/messages/" + messageId + "/reaction";
    }

    ResultActions putReaction(Fixture f, Long user, Long messageId, String body) throws Exception {
        return mvc.perform(put(path(f, messageId)).header("Authorization", auth(user))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    List<ChatReactionResponse> historyReactions(Fixture f, Long messageId) {
        return query.messages(f.owner(), f.room(), null, null, 50).items().stream()
                .filter(m -> m.messageId().equals(messageId)).findFirst().orElseThrow().reactions();
    }

    Long join(Fixture f) {
        return tx.execute(s -> {
            User user = users.save(User.signUp("chat-rich-extra-" + UUID.randomUUID() + "@test.dev"));
            members.save(HouseMember.create(houses.getReferenceById(f.house()), user, HouseMemberRole.MEMBER));
            return user.getId();
        });
    }

    @Test
    void HTTP로_공감을_남기고_바꾸고_취소하며_같은_요청을_반복해도_200이다() throws Exception {
        Fixture f = fixture();
        ChatMessageResponse message = sendText(f, f.owner(), "공감해 줘");
        long id = message.messageId();

        putReaction(f, f.member(), id, "{\"type\":\"HEART\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value(id))
                .andExpect(jsonPath("$.sequence").value(message.sequence()))
                .andExpect(jsonPath("$.reactions.length()").value(1))
                .andExpect(jsonPath("$.reactions[0].type").value("HEART"))
                .andExpect(jsonPath("$.reactions[0].userIds[0]").value(f.member()));
        // 같은 유형을 다시 보내도 그대로임.
        putReaction(f, f.member(), id, "{\"type\":\"HEART\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.reactions[0].userIds.length()").value(1));
        // 본인 메시지에도 공감할 수 있음.
        putReaction(f, f.owner(), id, "{\"type\":\"HEART\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reactions[0].userIds[0]").value(f.member()))
                .andExpect(jsonPath("$.reactions[0].userIds[1]").value(f.owner()));
        // 다른 유형으로 바꾸면 한 사람의 공감은 하나만 남음.
        putReaction(f, f.member(), id, "{\"type\":\"SAD\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reactions.length()").value(2))
                .andExpect(jsonPath("$.reactions[0].type").value("HEART"))
                .andExpect(jsonPath("$.reactions[0].userIds[0]").value(f.owner()))
                .andExpect(jsonPath("$.reactions[1].type").value("SAD"))
                .andExpect(jsonPath("$.reactions[1].userIds[0]").value(f.member()));
        assertThat(jdbc.queryForObject("select count(*) from chat_message_reactions where message_id = ?", Long.class, id))
                .isEqualTo(2);

        mvc.perform(delete(path(f, id)).header("Authorization", auth(f.member())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value(id))
                .andExpect(jsonPath("$.reactions.length()").value(1))
                .andExpect(jsonPath("$.reactions[0].type").value("HEART"));
        // 남긴 공감이 없어도 취소는 성공함.
        mvc.perform(delete(path(f, id)).header("Authorization", auth(f.member())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reactions.length()").value(1));
        assertThat(historyReactions(f, id)).containsExactly(new ChatReactionResponse(ChatReactionType.HEART, List.of(f.owner())));
    }

    @Test
    void 잘못된_유형과_인증_없는_요청과_권한_없는_사람은_거부한다() throws Exception {
        Fixture f = fixture();
        long id = sendText(f, f.owner(), "공감 대상").messageId();

        putReaction(f, f.member(), id, "{\"type\":\"ANGRY\"}").andExpect(status().isBadRequest());
        putReaction(f, f.member(), id, "{}").andExpect(status().isBadRequest());
        mvc.perform(put(path(f, id)).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"HEART\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(path(f, id))).andExpect(status().isUnauthorized());
        putReaction(f, f.outsider(), id, "{\"type\":\"HEART\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CHAT_FORBIDDEN"));
        mvc.perform(delete(path(f, id)).header("Authorization", auth(f.outsider())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CHAT_FORBIDDEN"));

        // 집을 나간 사람, 탈퇴한 사람, 봇은 공감할 수 없음.
        Long left = join(f);
        membershipCommands.leave(left, f.house());
        error(() -> reactions.react(left, f.room(), id, ChatReactionType.HEART), "CHAT_FORBIDDEN");
        Long bot = join(f);
        jdbc.update("update users set is_bot = true where id = ?", bot);
        error(() -> reactions.react(bot, f.room(), id, ChatReactionType.HEART), "CHAT_FORBIDDEN");
        error(() -> reactions.remove(bot, f.room(), id), "CHAT_FORBIDDEN");
        error(() -> reactions.react(f.member(), Long.MAX_VALUE, id, ChatReactionType.HEART), "CHAT_ROOM_NOT_FOUND");
        assertThat(jdbc.queryForObject("select count(*) from chat_message_reactions where message_id = ?", Long.class, id))
                .isZero();
    }

    @Test
    void 없는_메시지_다른_방의_메시지_운영자가_삭제한_메시지는_404다() throws Exception {
        Fixture f = fixture();
        Fixture other = fixture();
        long otherRoomMessage = sendText(other, other.owner(), "다른 방").messageId();
        long deleted = sendText(f, f.owner(), "지워질 글").messageId();
        reactions.react(f.member(), f.room(), deleted, ChatReactionType.LIKE);
        deleteMessage(deleted);

        for (long id : List.of(Long.MAX_VALUE, otherRoomMessage, deleted)) {
            putReaction(f, f.member(), id, "{\"type\":\"HEART\"}")
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CHAT_MESSAGE_NOT_FOUND"));
            mvc.perform(delete(path(f, id)).header("Authorization", auth(f.member())))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CHAT_MESSAGE_NOT_FOUND"));
        }
        // 삭제된 메시지는 기록에서도 공감을 비움.
        assertThat(historyReactions(f, deleted)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from chat_message_reactions where message_id = ?", Long.class,
                otherRoomMessage)).isZero();
    }

    @Test
    void 응답의_공감은_유형_선언_순서와_누른_순서로_묶이고_전송_재시도_응답에도_담긴다() throws Exception {
        Fixture f = fixture();
        Long third = join(f);
        UUID requestId = UUID.randomUUID();
        var request = photos(requestId, "사진 설명", uploaded(f, f.owner(), 2));
        long id = commands.send(f.owner(), f.room(), request).messageId();
        long plain = sendText(f, f.owner(), "공감 없는 글").messageId();

        reactions.react(third, f.room(), id, ChatReactionType.SAD);
        reactions.react(f.member(), f.room(), id, ChatReactionType.LIKE);
        reactions.react(f.owner(), f.room(), id, ChatReactionType.LIKE);

        var expected = List.of(new ChatReactionResponse(ChatReactionType.LIKE, List.of(f.member(), f.owner())),
                new ChatReactionResponse(ChatReactionType.SAD, List.of(third)));
        assertThat(historyReactions(f, id)).isEqualTo(expected);
        assertThat(historyReactions(f, plain)).isEmpty();
        assertThat(commands.send(f.owner(), f.room(), request).reactions()).isEqualTo(expected);

        // 유형을 바꾸면 그 유형의 맨 뒤로 감.
        reactions.react(f.member(), f.room(), id, ChatReactionType.SAD);
        assertThat(historyReactions(f, id)).containsExactly(
                new ChatReactionResponse(ChatReactionType.LIKE, List.of(f.owner())),
                new ChatReactionResponse(ChatReactionType.SAD, List.of(third, f.member())));
    }

    @Test
    void 탈퇴한_회원의_공감은_응답에서_빠진다() throws Exception {
        Fixture f = fixture();
        long id = sendText(f, f.owner(), "탈퇴 전 공감").messageId();
        reactions.react(f.member(), f.room(), id, ChatReactionType.HEART);
        reactions.react(f.owner(), f.room(), id, ChatReactionType.CHECK);

        withdrawal.withdraw(f.member());

        var expected = List.of(new ChatReactionResponse(ChatReactionType.CHECK, List.of(f.owner())));
        assertThat(historyReactions(f, id)).isEqualTo(expected);
        assertThat(reactions.react(f.owner(), f.room(), id, ChatReactionType.CHECK).reactions()).isEqualTo(expected);
    }

    @Test
    void 공감은_읽음_상태와_순번을_바꾸지_않고_푸시를_보내지_않는다() throws Exception {
        Fixture f = fixture();
        var message = sendText(f, f.owner(), "조용한 공감");
        verify(fcm, org.mockito.Mockito.timeout(5_000)).pushWithoutRecord(anyLong(), anyString(), anyString(), anyMap());
        clearInvocations(fcm);
        var before = query.get(f.owner(), f.room());

        reactions.react(f.member(), f.room(), message.messageId(), ChatReactionType.LAUGH);
        reactions.remove(f.member(), f.room(), message.messageId());

        assertThat(query.get(f.owner(), f.room())).isEqualTo(before);
        assertThat(query.messages(f.owner(), f.room(), null, null, 50).items().getFirst().unreadCount()).isEqualTo(1);
        verify(fcm, after(1_000).never()).pushWithoutRecord(anyLong(), anyString(), anyString(), anyMap());
    }

    @Test
    void 같은_사람의_동시_공감은_한_행만_남기고_모두_성공한다() throws Exception {
        Fixture f = fixture();
        long id = sendText(f, f.owner(), "동시 공감").messageId();
        ChatReactionType[] types = ChatReactionType.values();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                ChatReactionType type = types[i % types.length];
                futures.add(pool.submit(() -> {
                    start.await();
                    return reactions.react(f.member(), f.room(), id, type);
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select count(*) from chat_message_reactions where message_id = ? and user_id = ?",
                Long.class, id, f.member())).isEqualTo(1);
    }

    @Test
    void 실제_WebSocket에서_공감_프레임은_옵트인한_연결에만_간다() throws Exception {
        Fixture f = fixture();
        long id = sendText(f, f.owner(), "소켓 공감").messageId();
        try (Socket optedIn = connect(f.owner(), f.room(), Map.of("includeMessages", true, "includeReactions", true));
             Socket reactionsOnly = connect(f.member(), f.room(), Map.of("includeReactions", true));
             Socket legacy = connect(f.owner(), f.room(), Map.of());
             Socket bodyOnly = connect(f.member(), f.room(), Map.of("includeMessages", true))) {
            JsonNode ready = optedIn.await(type("READY"));
            assertThat(ready.path("includeReactions").asBoolean()).isTrue();
            assertThat(ready.path("includeMessages").asBoolean()).isTrue();
            JsonNode reactionsReady = reactionsOnly.await(type("READY"));
            assertThat(reactionsReady.path("includeReactions").asBoolean()).isTrue();
            assertThat(reactionsReady.has("includeMessages")).isFalse();
            // 옵션을 보내지 않은 기존 연결의 READY는 형식이 그대로임.
            JsonNode legacyReady = legacy.await(type("READY"));
            assertThat(legacyReady.has("includeReactions")).isFalse();
            assertThat(legacyReady.has("includeMessages")).isFalse();
            assertThat(bodyOnly.await(type("READY")).has("includeReactions")).isFalse();

            reactions.react(f.member(), f.room(), id, ChatReactionType.HEART);

            for (Socket socket : List.of(optedIn, reactionsOnly)) {
                JsonNode frame = socket.await(type("MESSAGE_REACTIONS"));
                assertThat(frame.path("roomId").asLong()).isEqualTo(f.room());
                assertThat(frame.path("messageId").asLong()).isEqualTo(id);
                assertThat(frame.path("sequence").asLong()).isEqualTo(1);
                assertThat(frame.path("reactions").size()).isEqualTo(1);
                assertThat(frame.at("/reactions/0/type").asString()).isEqualTo("HEART");
                assertThat(frame.at("/reactions/0/userIds/0").asLong()).isEqualTo(f.member());
            }
            reactions.remove(f.member(), f.room(), id);
            assertThat(optedIn.await(type("MESSAGE_REACTIONS")).path("reactions").size()).isZero();
            assertThat(reactionsOnly.await(type("MESSAGE_REACTIONS")).path("reactions").size()).isZero();

            // 새 메시지가 오면 모든 연결이 프레임을 받으므로, 그때까지 받은 프레임으로 공감 프레임이 없었음을 확인함.
            sendText(f, f.owner(), "경계 메시지");
            legacy.await(node -> node.path("room").path("lastSequence").asLong() == 2);
            bodyOnly.await(node -> node.path("room").path("lastSequence").asLong() == 2);
            assertThat(legacy.received).noneMatch(event -> event.contains("MESSAGE_REACTIONS"));
            assertThat(bodyOnly.received).noneMatch(event -> event.contains("MESSAGE_REACTIONS"));
            // 본문 프레임에도 그 시점의 공감이 담김.
            long second = optedIn.await(type("MESSAGE_CREATED")).path("message").path("messageId").asLong();
            reactions.react(f.owner(), f.room(), second, ChatReactionType.CHECK);
            assertThat(optedIn.await(type("MESSAGE_REACTIONS")).path("sequence").asLong()).isEqualTo(2);
        }
    }

    @Test
    void 롤백된_공감은_저장도_전달도_되지_않는다() throws Exception {
        Fixture f = fixture();
        long id = sendText(f, f.owner(), "롤백 공감").messageId();
        try (Socket socket = connect(f.owner(), f.room(), Map.of("includeReactions", true))) {
            socket.await(type("READY"));
            tx.executeWithoutResult(status -> {
                reactions.react(f.member(), f.room(), id, ChatReactionType.HEART);
                status.setRollbackOnly();
            });
            reactions.react(f.member(), f.room(), id, ChatReactionType.LIKE);

            JsonNode frame = socket.await(type("MESSAGE_REACTIONS"));
            assertThat(frame.at("/reactions/0/type").asString()).isEqualTo("LIKE");
            assertThat(socket.received).filteredOn(event -> event.contains("MESSAGE_REACTIONS")).hasSize(1);
        }
    }

    static Predicate<JsonNode> type(String type) { return node -> node.path("type").asString().equals(type); }

    Socket connect(Long user, Long room, Map<String, Object> options) throws Exception {
        Socket listener = new Socket();
        listener.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", "https://dkfiwkal2ezg9.cloudfront.net")
                .buildAsync(URI.create("ws://localhost:" + port + "/api/v1/chat/ws"), listener).get(10, TimeUnit.SECONDS);
        Map<String, Object> subscribe = new HashMap<>(options);
        subscribe.put("type", "SUBSCRIBE");
        subscribe.put("roomId", room);
        subscribe.put("accessToken", tokens.issueAccessToken(user, MemberRole.NORMAL));
        listener.socket.sendText(mapper.writeValueAsString(subscribe), true).join();
        return listener;
    }

    class Socket implements WebSocket.Listener, AutoCloseable {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        final List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder partial = new StringBuilder();
        WebSocket socket;
        public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence text, boolean last) {
            partial.append(text);
            if (last) {
                received.add(partial.toString());
                events.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }
        public CompletionStage<?> onClose(WebSocket webSocket, int code, String reason) { closed.complete(code); return null; }
        JsonNode await(Predicate<JsonNode> predicate) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                String event = events.poll(500, TimeUnit.MILLISECONDS);
                if (event != null) {
                    JsonNode node = mapper.readTree(event);
                    if (predicate.test(node)) return node;
                }
            }
            throw new AssertionError("기대한 채팅 소켓 이벤트가 오지 않음: " + received);
        }
        public void close() { if (socket != null) socket.abort(); }
    }
}
