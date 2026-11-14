package com.triples.rougether.userapi.chat.realtime;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.triples.rougether.userapi.chat.dto.ChatMessageResponse;
import com.triples.rougether.userapi.chat.dto.ChatReactionResponse;
import com.triples.rougether.userapi.chat.dto.ChatRoomResponse;
import com.triples.rougether.userapi.chat.service.ChatQueryService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

// 쓰기는 HTTP 트랜잭션으로 처리하고 커밋 후 소켓 본문 전달을 즉시 시작함.
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatSocketHandler extends TextWebSocketHandler {
    private static final int PAGE_SIZE = 50;
    private static final long SEND_TIMEOUT_MS = 3000;
    // 한 연결이 아직 보내지 못한 공감 변경(메시지 수) 상한. 넘으면 연결을 끊어 재접속·기록 조회로 복구하게 함.
    private static final int MAX_PENDING_REACTIONS = 256;
    private final ChatQueryService query;
    private final TokenService tokens;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private long nextReconcile;
    private final ThreadPoolExecutor outbound = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), Thread.ofPlatform().daemon().name("chat-send-", 0).factory());

    @Override
    public synchronized void afterConnectionEstablished(WebSocketSession session) throws IOException {
        if (connections.size() >= 5000) {
            session.close(CloseStatus.SERVICE_OVERLOAD);
            return;
        }
        session.setTextMessageSizeLimit(8192);
        connections.put(session.getId(), new Connection(
                new ConcurrentWebSocketSessionDecorator(session, (int) SEND_TIMEOUT_MS, 16384), clock.millis()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Connection connection = connections.get(session.getId());
        if (connection == null) return;
        try {
            if (message.getPayloadLength() > 8192 || clock.millis() - connection.openedAt >= 10000) {
                close(connection, CloseStatus.POLICY_VIOLATION);
                return;
            }
            Subscribe request = mapper.readValue(message.getPayload(), Subscribe.class);
            if (!"SUBSCRIBE".equals(request.type()) || request.roomId() == null || request.roomId() <= 0
                    || request.accessToken() == null) {
                close(connection, CloseStatus.POLICY_VIOLATION);
                return;
            }
            Long userId = tokens.parseAccessToken(request.accessToken()).id();
            synchronized (this) {
                long count = connections.values().stream().filter(c -> c.subscription != null
                        && c.subscription.userId().equals(userId)).count();
                if (connection.subscription != null || count >= 10) {
                    close(connection, CloseStatus.POLICY_VIOLATION);
                    return;
                }
                connection.subscription = new Subscription(userId, request.roomId(), request.accessToken(),
                        Boolean.TRUE.equals(request.includeMessages()), Boolean.TRUE.equals(request.includeReactions()));
            }
            requestSync(connection);
        } catch (BusinessException | IllegalArgumentException e) {
            close(connection, CloseStatus.POLICY_VIOLATION);
        } catch (tools.jackson.core.JacksonException e) {
            close(connection, CloseStatus.BAD_DATA);
        } catch (Exception e) {
            close(connection, CloseStatus.SERVER_ERROR);
        }
    }

    // 이 인스턴스에서 그 방을 구독 중인지(= 채팅 화면을 보는 중). 채팅 푸시 생략 판정용(#435).
    // 인스턴스 메모리 기준이라 여러 인스턴스가 동시에 서비스하거나 배포 전환 중이면 틀릴 수 있음.
    public boolean isWatching(Long userId, Long roomId) {
        return connections.values().stream().map(c -> c.subscription).anyMatch(subscription -> subscription != null
                && subscription.userId().equals(userId) && subscription.roomId().equals(roomId));
    }

    public void changed(Long roomId) {
        for (Connection connection : connections.values()) {
            var subscription = connection.subscription;
            if (subscription != null && subscription.roomId().equals(roomId)) requestSync(connection);
        }
    }

    // 공감이 바뀐 메시지를 includeReactions로 구독한 연결에만 알림. 구버전 앱은 모르는 type을 받지 않음.
    // 내용은 프레임을 쓰기 직전에 DB에서 읽으므로 신호가 겹치거나 순서가 바뀌어도 마지막 프레임이 최신 값임.
    // 신호가 유실되면 다시 보내지 않음. 클라이언트는 기록 조회의 reactions로 복구함.
    public void reactionsChanged(Long roomId, Long messageId) {
        for (Connection connection : connections.values()) {
            var subscription = connection.subscription;
            if (subscription == null || !subscription.includeReactions() || !subscription.roomId().equals(roomId)) continue;
            if (!connection.reactionUpdates.contains(messageId)
                    && connection.reactionUpdates.size() >= MAX_PENDING_REACTIONS) {
                close(connection, CloseStatus.SERVICE_OVERLOAD);
                continue;
            }
            connection.reactionUpdates.add(messageId);
            schedule(connection);
        }
    }

    // 주기는 장애 복구·연결 정리에만 사용함. 정상 커밋/Redis 알림은 changed에서 즉시 처리함.
    @Scheduled(fixedDelay = 1000, scheduler = "chatScheduler")
    public void flush() {
        long now = clock.millis();
        boolean reconcile = now >= nextReconcile;
        if (reconcile) nextReconcile = now + 5000;
        for (Connection connection : connections.values()) {
            long started = connection.sendStartedAt;
            if (started >= 0 && now - started >= SEND_TIMEOUT_MS) {
                close(connection, CloseStatus.SESSION_NOT_RELIABLE);
            } else if (connection.subscription == null) {
                if (now - connection.openedAt >= 10000) close(connection, CloseStatus.POLICY_VIOLATION);
            } else if (reconcile) {
                requestSync(connection);
            }
        }
    }

    // 방 상태(메시지·읽음)가 바뀌었을 수 있음을 표시하고 송신 작업을 요청함.
    private void requestSync(Connection connection) {
        if (!active(connection)) return;
        connection.roomChanged.set(true);
        schedule(connection);
    }

    private void schedule(Connection connection) {
        if (!active(connection)) return;
        // 신호는 합쳐도 본문은 버리지 않음. 작업 중 도착한 신호는 종료 직후 다시 처리함.
        connection.pending.set(true);
        if (!connection.running.compareAndSet(false, true)) return;
        try {
            outbound.execute(() -> synchronize(connection));
        } catch (RejectedExecutionException e) {
            connection.running.set(false);
            close(connection, CloseStatus.SERVICE_OVERLOAD);
        }
    }

    private void synchronize(Connection connection) {
        connection.pending.set(false);
        try {
            if (!active(connection)) return;
            var subscription = connection.subscription;
            tokens.parseAccessToken(subscription.accessToken());
            // 공감만 바뀐 실행에서는 방 상태를 다시 읽거나 ROOM_UPDATED를 보내지 않음.
            boolean roomChanged = connection.roomChanged.getAndSet(false);
            if (!connection.ready) {
                ChatRoomResponse state = query.get(subscription.userId(), subscription.roomId());
                // READY 시점 이전 기록은 HTTP로 조회함. 이후 커밋은 이 순서부터 이어 보냄.
                connection.sentSequence = state.lastSequence();
                // 옵션을 보내지 않은 기존 연결의 READY는 형식을 그대로 유지함.
                Object ready = subscription.includeMessages() || subscription.includeReactions()
                        ? new Ready("READY", state, subscription.includeMessages() ? Boolean.TRUE : null,
                                subscription.includeReactions() ? Boolean.TRUE : null)
                        : new Update("READY", state);
                if (!send(connection, ready)) return;
                connection.ready = true;
            } else if (roomChanged && !sendRoomChanges(connection, subscription)) {
                return;
            }
            sendReactions(connection, subscription);
        } catch (BusinessException e) {
            close(connection, CloseStatus.POLICY_VIOLATION);
        } catch (IOException e) {
            close(connection, CloseStatus.SESSION_NOT_RELIABLE);
        } catch (Exception e) {
            log.warn("채팅 소켓 동기화 실패: roomId={}", connection.subscription.roomId());
            close(connection, CloseStatus.SERVER_ERROR);
        } finally {
            connection.running.set(false);
            if (connection.pending.get()) schedule(connection);
        }
    }

    // 새 본문과 방 상태를 보냄. 이어서 보낼 것이 남았거나 연결이 끊겼으면 false.
    private boolean sendRoomChanges(Connection connection, Subscription subscription) throws IOException {
        ChatRoomResponse state = query.get(subscription.userId(), subscription.roomId());
        if (subscription.includeMessages() && state.lastSequence() > connection.sentSequence) {
            var page = query.messages(subscription.userId(), subscription.roomId(), null,
                    connection.sentSequence, PAGE_SIZE);
            for (ChatMessageResponse message : page.items()) {
                if (message.sequence() != connection.sentSequence + 1) {
                    close(connection, CloseStatus.SESSION_NOT_RELIABLE);
                    return false;
                }
                if (!send(connection, new MessageCreated("MESSAGE_CREATED", message))) return false;
                // 소켓 쓰기 완료 위치이며 클라이언트의 수신/읽음 확인으로 간주하지 않음.
                connection.sentSequence = message.sequence();
            }
            state = page.room();
            if (page.hasNext()) {
                // 한 연결이 작업 스레드를 독점하지 않도록 한 페이지 후 대기열 뒤로 보냄.
                connection.roomChanged.set(true);
                connection.pending.set(true);
                return false;
            }
        }
        // 본문보다 상태를 먼저 알리면 프론트가 불필요한 HTTP 복구를 시작하므로 마지막에 보냄.
        return send(connection, new Update("ROOM_UPDATED", state));
    }

    // 공감이 바뀐 메시지마다 현재 값을 읽어 보냄. 읽기 전에 대기 목록에서 빼므로 읽은 뒤의 변경은 다시 신호가 옴.
    private void sendReactions(Connection connection, Subscription subscription) throws IOException {
        if (!subscription.includeReactions()) return;
        for (Long messageId : List.copyOf(connection.reactionUpdates)) {
            connection.reactionUpdates.remove(messageId);
            var current = query.liveReactions(subscription.roomId(), messageId).orElse(null);
            // 그사이 운영자가 삭제한 메시지는 보내지 않음.
            if (current == null) continue;
            // 본문을 직접 받는 연결이 아직 받지 못한 메시지는 뒤따르는 MESSAGE_CREATED가 최신 공감을 실어 감.
            if (subscription.includeMessages() && current.sequence() > connection.sentSequence) continue;
            if (!send(connection, new MessageReactions("MESSAGE_REACTIONS", subscription.roomId(),
                    current.messageId(), current.sequence(), current.reactions()))) return;
        }
    }

    private boolean send(Connection connection, Object event) throws IOException {
        if (!active(connection)) return false;
        var subscription = connection.subscription;
        // 앞선 소켓 쓰기가 지연되는 동안 탈퇴/강퇴/토큰 만료가 발생할 수 있어 프레임마다 재검사함.
        query.requireReadable(subscription.userId(), subscription.roomId());
        tokens.parseAccessToken(subscription.accessToken());
        if (!active(connection)) return false;
        TextMessage message = new TextMessage(mapper.writeValueAsString(event));
        connection.sendStartedAt = clock.millis();
        try {
            connection.session.sendMessage(message);
            return active(connection);
        } finally {
            connection.sendStartedAt = -1;
        }
    }

    private boolean active(Connection connection) {
        return connections.get(connection.session.getId()) == connection && connection.session.isOpen();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connections.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Connection connection = connections.get(session.getId());
        if (connection != null) close(connection, CloseStatus.SERVER_ERROR);
    }

    private void close(Connection connection, CloseStatus status) {
        if (!connections.remove(connection.session.getId(), connection)) return;
        try { connection.session.close(status); } catch (IOException ignored) { }
    }

    @PreDestroy
    public void shutdown() {
        connections.values().forEach(c -> close(c, CloseStatus.GOING_AWAY));
        outbound.shutdownNow();
    }

    private static class Connection {
        final WebSocketSession session;
        final long openedAt;
        final AtomicBoolean running = new AtomicBoolean();
        final AtomicBoolean pending = new AtomicBoolean();
        final AtomicBoolean roomChanged = new AtomicBoolean();
        final Set<Long> reactionUpdates = ConcurrentHashMap.newKeySet();
        volatile Subscription subscription;
        volatile long sendStartedAt = -1;
        boolean ready;
        long sentSequence;
        Connection(WebSocketSession session, long openedAt) { this.session = session; this.openedAt = openedAt; }
    }
    private record Subscription(Long userId, Long roomId, String accessToken, boolean includeMessages,
                                boolean includeReactions) {}
    public record Subscribe(String type, Long roomId, String accessToken, Boolean includeMessages,
                            Boolean includeReactions) {}
    // 켠 옵션만 true로 되돌려 줌. 켜지 않은 옵션은 필드를 싣지 않음.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Ready(String type, ChatRoomResponse room, Boolean includeMessages, Boolean includeReactions) {}
    public record Update(String type, ChatRoomResponse room) {}
    public record MessageCreated(String type, ChatMessageResponse message) {}
    public record MessageReactions(String type, Long roomId, Long messageId, long sequence,
                                   List<ChatReactionResponse> reactions) {}
}
