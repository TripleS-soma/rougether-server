package com.triples.rougether.userapi.chat;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.*;
import com.triples.rougether.domain.house.repository.*;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.chat.dto.*;
import com.triples.rougether.userapi.chat.service.*;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.house.service.HouseMemberCommandService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ChatIntegrationTest {
    @Autowired ChatCommandService commands;
    @Autowired ChatQueryService query;
    @Autowired UserRepository users;
    @Autowired HouseRepository houses;
    @Autowired HouseMemberRepository members;
    @Autowired HouseMemberCommandService membershipCommands;
    @Autowired TransactionTemplate tx;
    @Autowired TokenService tokens;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final List<Fixture> fixtures = new ArrayList<>();
    @LocalServerPort int port;
    @Autowired org.springframework.context.ApplicationContext context;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.triples.rougether.userapi.chat.realtime.ChatChangePublisher publisher;

    @Test
    void 채팅_스케줄러는_다른_도메인의_스케줄러와_분리된다() {
        assertThat(context.getBean("chatScheduler")).isNotSameAs(context.getBean("taskScheduler"));
    }

    record Fixture(Long owner, Long member, Long outsider, Long house, Long membership, Long room) {}

    Fixture fixture() {
        Fixture fixture = tx.execute(status -> {
            String key = UUID.randomUUID().toString();
            User owner = users.save(User.signUp("chat-owner-" + key + "@test.dev"));
            User member = users.save(User.signUp("chat-member-" + key + "@test.dev"));
            User outsider = users.save(User.signUp("chat-other-" + key + "@test.dev"));
            House house = houses.save(House.create(owner, "채팅 테스트", null, null, 4,
                    key.substring(0, 8), Instant.now().plusSeconds(3600)));
            members.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
            HouseMember membership = members.save(HouseMember.create(house, member, HouseMemberRole.MEMBER));
            house.increaseMemberCount();
            Long room = commands.openHouse(owner.getId(), house.getId()).roomId();
            return new Fixture(owner.getId(), member.getId(), outsider.getId(), house.getId(), membership.getId(), room);
        });
        fixtures.add(fixture);
        return fixture;
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        tx.executeWithoutResult(status -> {
            for (Fixture f : fixtures) {
                Set<Long> userIds = new HashSet<>(jdbc.queryForList(
                        "select user_id from house_members where house_id = ?", Long.class, f.house()));
                userIds.add(f.outsider());
                jdbc.update("delete from chat_messages where room_id = ?", f.room());
                jdbc.update("delete from chat_read_states where room_id = ?", f.room());
                jdbc.update("delete from chat_rooms where id = ?", f.room());
                jdbc.update("delete from house_members where house_id = ?", f.house());
                jdbc.update("delete from house where id = ?", f.house());
                for (Long userId : userIds) {
                    jdbc.update("delete from notification where user_id = ?", userId);
                    jdbc.update("delete from user_daily_activity where user_id = ?", userId);
                    jdbc.update("delete from users where id = ?", userId);
                }
            }
        });
    }

    ChatMessageResponse send(Fixture f, Long user, String content) {
        return commands.send(user, f.room(), new ChatSendRequest(UUID.randomUUID(), content));
    }

    long readSequence(ChatRoomResponse room, Long userId) {
        return room.readers().stream().filter(r -> r.userId().equals(userId)).findFirst().orElseThrow().lastReadSequence();
    }

    @Test
    void 집별_방은_하나이며_다른_집과_비구성원은_격리된다() {
        Fixture f = fixture();
        Fixture other = fixture();
        assertThat(commands.openHouse(f.member(), f.house()).roomId()).isEqualTo(f.room());
        assertThat(other.room()).isNotEqualTo(f.room());
        assertThatThrownBy(() -> commands.openHouse(f.outsider(), f.house())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.messages(other.owner(), f.room(), null, null, 50)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> commands.send(f.outsider(), f.room(), new ChatSendRequest(UUID.randomUUID(), "침입")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> commands.read(f.outsider(), f.room(), 0)).isInstanceOf(BusinessException.class);
    }

    @Test
    void 멱등키는_중복전송을_막고_내용이_다르면_거부한다() {
        Fixture f = fixture();
        var request = new ChatSendRequest(UUID.randomUUID(), "반가워요 👋");
        var first = commands.send(f.owner(), f.room(), request);
        var retry = commands.send(f.owner(), f.room(), request);
        assertThat(retry.messageId()).isEqualTo(first.messageId());
        assertThat(query.get(f.owner(), f.room()).lastSequence()).isEqualTo(1);
        assertThatThrownBy(() -> commands.send(f.owner(), f.room(), new ChatSendRequest(request.clientMessageId(), "변경")))
                .isInstanceOf(BusinessException.class);
        // 동일 UUID라도 발신자가 다르면 독립 요청임.
        assertThat(commands.send(f.member(), f.room(), request).sequence()).isEqualTo(2);
    }

    @Test
    void 읽음은_화면에서_확인한_지점만_전진하고_다른_기기의_과거_요청은_무시한다() {
        Fixture f = fixture();
        assertThat(send(f, f.owner(), "하나").unreadCount()).isEqualTo(1);
        send(f, f.owner(), "둘");
        send(f, f.owner(), "셋");
        assertThat(readSequence(query.get(f.owner(), f.room()), f.owner())).isZero();
        commands.read(f.member(), f.room(), 2);
        var page = query.messages(f.owner(), f.room(), null, 0L, 50);
        assertThat(page.items()).extracting(ChatMessageResponse::unreadCount).containsExactly(0L, 0L, 1L);
        assertThat(readSequence(commands.read(f.member(), f.room(), 1), f.member())).isEqualTo(2);
        assertThatThrownBy(() -> commands.read(f.member(), f.room(), 4)).isInstanceOf(BusinessException.class);
        assertThat(readSequence(query.get(f.owner(), f.room()), f.member())).isEqualTo(2);
    }

    @Test
    void 이전_기록과_재접속_커서는_새_메시지가_와도_누락되지_않는다() {
        Fixture f = fixture();
        for (int i = 1; i <= 5; i++) send(f, f.owner(), "메시지 " + i);
        var first = query.messages(f.member(), f.room(), null, null, 2);
        assertThat(first.items()).extracting(ChatMessageResponse::sequence).containsExactly(5L, 4L);
        send(f, f.owner(), "추가");
        var older = query.messages(f.member(), f.room(), first.nextCursor(), null, 2);
        assertThat(older.items()).extracting(ChatMessageResponse::sequence).containsExactly(3L, 2L);
        var missed = query.messages(f.member(), f.room(), null, 3L, 2);
        assertThat(missed.items()).extracting(ChatMessageResponse::sequence).containsExactly(4L, 5L);
        assertThat(missed.hasNext()).isTrue();
        assertThat(query.messages(f.member(), f.room(), null, missed.nextCursor(), 2).items())
                .extracting(ChatMessageResponse::sequence).containsExactly(6L);
    }

    @Test
    void 동시_재시도와_읽음요청은_DB에서_직렬화된다() throws Exception {
        Fixture f = fixture();
        var request = new ChatSendRequest(UUID.randomUUID(), "동시 전송");
        try (var executor = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            List<Future<ChatMessageResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> {
                start.await();
                return commands.send(f.owner(), f.room(), request);
            }));
            start.countDown();
            Set<Long> ids = new HashSet<>();
            for (var future : futures) ids.add(future.get(20, TimeUnit.SECONDS).messageId());
            assertThat(ids).hasSize(1);
            assertThat(query.get(f.owner(), f.room()).lastSequence()).isEqualTo(1);
            for (int i = 2; i <= 8; i++) send(f, f.owner(), "순서 " + i);
            List<Future<?>> reads = new ArrayList<>();
            for (long i = 8; i >= 1; i--) {
                long sequence = i;
                reads.add(executor.submit(() -> commands.read(f.member(), f.room(), sequence)));
            }
            for (var future : reads) future.get(20, TimeUnit.SECONDS);
            assertThat(readSequence(query.get(f.owner(), f.room()), f.member())).isEqualTo(8);
        }
    }

    @Test
    void 롤백된_전송은_본문과_순서를_남기지_않는다() {
        Fixture f = fixture();
        tx.executeWithoutResult(status -> {
            send(f, f.owner(), "롤백됨");
            status.setRollbackOnly();
        });
        org.mockito.Mockito.verify(publisher, org.mockito.Mockito.never()).committed(org.mockito.ArgumentMatchers.any());
        assertThat(query.messages(f.member(), f.room(), null, null, 50).items()).isEmpty();
        assertThat(send(f, f.owner(), "정상 커밋").sequence()).isEqualTo(1);
        org.mockito.Mockito.verify(publisher).committed(new ChatRoomChanged(f.room()));
    }

    @Test
    void 탈퇴_강퇴_계정삭제_집삭제는_발신_조회_읽음을_차단한다() {
        Fixture f = fixture();
        send(f, f.owner(), "기록");
        membershipCommands.kick(f.owner(), f.house(), f.membership());
        assertThatThrownBy(() -> query.get(f.member(), f.room())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> send(f, f.member(), "불가")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> commands.read(f.member(), f.room(), 1)).isInstanceOf(BusinessException.class);
        assertThat(query.messages(f.owner(), f.room(), null, null, 50).items().getFirst().unreadCount()).isZero();
        Fixture left = fixture();
        membershipCommands.leave(left.member(), left.house());
        assertThatThrownBy(() -> query.get(left.member(), left.room())).isInstanceOf(BusinessException.class);
        tx.executeWithoutResult(s -> users.findById(left.owner()).orElseThrow().softDelete(Instant.now()));
        assertThatThrownBy(() -> query.get(left.owner(), left.room())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> send(left, left.owner(), "탈퇴 계정")).isInstanceOf(BusinessException.class);
    }

    @Test
    void 동시_신규_메시지는_중복없는_연속_순서를_받는다() throws Exception {
        Fixture f = fixture();
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Future<ChatMessageResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                Long userId = i % 2 == 0 ? f.owner() : f.member();
                futures.add(executor.submit(() -> send(f, userId, "동시 신규")));
            }
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }
        assertThat(query.messages(f.member(), f.room(), null, 0L, 100).items())
                .extracting(ChatMessageResponse::sequence).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    @Test
    void 봇은_읽음_집계와_채팅_접근에서_제외된다() {
        Fixture f = fixture();
        Long botId = tx.execute(status -> {
            User bot = users.save(User.bot("chat-" + UUID.randomUUID().toString().substring(0, 20), "테스트봇", "테스트"));
            members.save(HouseMember.create(houses.findById(f.house()).orElseThrow(), bot, HouseMemberRole.MEMBER));
            return bot.getId();
        });
        assertThat(send(f, f.owner(), "봇 제외").unreadCount()).isEqualTo(1);
        assertThat(query.get(f.owner(), f.room()).readers()).hasSize(2);
        assertThatThrownBy(() -> query.get(botId, f.room())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> send(f, botId, "불가")).isInstanceOf(BusinessException.class);
    }

    @Test
    void 삭제된_집과_탈퇴계정의_잔여_멤버십은_실시간_수신에서도_제외된다() {
        Fixture f = fixture();
        tx.executeWithoutResult(s -> users.findById(f.member()).orElseThrow().softDelete(Instant.now()));
        assertThat(query.liveSnapshot(f.room()).includes(f.member())).isFalse();
        tx.executeWithoutResult(s -> houses.findById(f.house()).orElseThrow().softDelete());
        assertThatThrownBy(() -> query.liveSnapshot(f.room())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.get(f.owner(), f.room())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> send(f, f.owner(), "삭제된 집")).isInstanceOf(BusinessException.class);
    }

    @Test
    void HTTP는_JWT와_본문_커서_검증을_적용한다() throws Exception {
        Fixture f = fixture();
        String path = "/api/v1/chat/rooms/" + f.room();
        String auth = "Bearer " + tokens.issueAccessToken(f.owner(), MemberRole.NORMAL);
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "Bearer " + tokens.issueAccessToken(f.outsider(), MemberRole.NORMAL)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CHAT_FORBIDDEN"));
        mvc.perform(post(path + "/messages").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new ChatSendRequest(UUID.randomUUID(), " ")))).andExpect(status().isBadRequest());
        mvc.perform(post(path + "/messages").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientMessageId\":\"bad\",\"content\":\"hello\"}")).andExpect(status().isBadRequest());
        mvc.perform(get(path + "/messages?before=2&after=0").header("Authorization", auth)).andExpect(status().isBadRequest());
        mvc.perform(get(path + "/messages?size=101").header("Authorization", auth)).andExpect(status().isBadRequest());
        mvc.perform(put(path + "/read").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post(path + "/messages").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new ChatSendRequest(UUID.randomUUID(), "HTTP 전송"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sequence").value(1))
                .andExpect(jsonPath("$.unreadCount").value(1));
    }

    @Test
    void 허용되지_않은_브라우저_Origin은_소켓_연결부터_거부한다() {
        assertThatThrownBy(() -> HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", "https://untrusted.example")
                .buildAsync(URI.create("ws://localhost:" + port + "/api/v1/chat/ws"), new WebSocket.Listener() {})
                .join()).hasCauseInstanceOf(java.net.http.WebSocketHandshakeException.class);
    }

    @Test
    void 실제_WebSocket으로_전송과_읽음이_전달되고_강퇴하면_연결이_종료된다() throws Exception {
        Fixture f = fixture();
        try (Socket owner = connect(f.owner(), f.room()); Socket member = connect(f.member(), f.room())) {
            owner.await(node -> node.path("type").asString().equals("READY"));
            member.await(node -> node.path("type").asString().equals("READY"));
            send(f, f.owner(), "실시간");
            member.await(node -> node.path("room").path("lastSequence").asLong() == 1);
            owner.await(node -> node.path("room").path("lastSequence").asLong() == 1);
            commands.read(f.member(), f.room(), 1);
            owner.await(node -> {
                for (JsonNode reader : node.path("room").path("readers"))
                    if (reader.path("userId").asLong() == f.member() && reader.path("lastReadSequence").asLong() == 1) return true;
                return false;
            });
            membershipCommands.kick(f.owner(), f.house(), f.membership());
            send(f, f.owner(), "강퇴 후");
            assertThat(member.closed.get(10, TimeUnit.SECONDS)).isEqualTo(1008);
            assertThat(query.messages(f.owner(), f.room(), null, 1L, 50).items()).hasSize(1);
        }
        try (Socket outsider = connect(f.outsider(), f.room())) {
            assertThat(outsider.closed.get(10, TimeUnit.SECONDS)).isEqualTo(1008);
            assertThat(outsider.events).isEmpty();
        }
    }

    Socket connect(Long user, Long room) throws Exception {
        Socket listener = new Socket();
        listener.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", "https://app.rougether.com")
                .buildAsync(URI.create("ws://localhost:" + port + "/api/v1/chat/ws"), listener).get(10, TimeUnit.SECONDS);
        listener.socket.sendText(mapper.writeValueAsString(Map.of("type", "SUBSCRIBE", "roomId", room,
                "accessToken", tokens.issueAccessToken(user, MemberRole.NORMAL))), true).join();
        return listener;
    }

    class Socket implements WebSocket.Listener, AutoCloseable {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder partial = new StringBuilder();
        WebSocket socket;
        public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence text, boolean last) {
            partial.append(text);
            if (last) { events.add(partial.toString()); partial.setLength(0); }
            webSocket.request(1);
            return null;
        }
        public CompletionStage<?> onClose(WebSocket webSocket, int code, String reason) { closed.complete(code); return null; }
        void await(Predicate<JsonNode> predicate) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                String event = events.poll(500, TimeUnit.MILLISECONDS);
                if (event != null && predicate.test(mapper.readTree(event))) return;
            }
            fail("기대한 채팅 소켓 이벤트가 오지 않음");
        }
        public void close() { if (socket != null) socket.abort(); }
    }
}
