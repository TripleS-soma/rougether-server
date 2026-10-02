package com.triples.rougether.userapi.chat.realtime;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.chat.dto.ChatMessageResponse;
import com.triples.rougether.userapi.chat.dto.ChatRoomResponse;
import com.triples.rougether.userapi.chat.service.ChatQueryService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
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
                        Boolean.TRUE.equals(request.includeMessages()));
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

    public void changed(Long roomId) {
        for (Connection connection : connections.values()) {
            var subscription = connection.subscription;
            if (subscription != null && subscription.roomId().equals(roomId)) requestSync(connection);
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

    private void requestSync(Connection connection) {
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
            ChatRoomResponse state = query.get(subscription.userId(), subscription.roomId());
            if (!connection.ready) {
                // READY 시점 이전 기록은 HTTP로 조회함. 이후 커밋은 이 순서부터 이어 보냄.
                connection.sentSequence = state.lastSequence();
                Object ready = subscription.includeMessages() ? new Ready("READY", state, true)
                        : new Update("READY", state);
                if (send(connection, ready)) connection.ready = true;
                return;
            }
            if (subscription.includeMessages() && state.lastSequence() > connection.sentSequence) {
                var page = query.messages(subscription.userId(), subscription.roomId(), null,
                        connection.sentSequence, PAGE_SIZE);
                for (ChatMessageResponse message : page.items()) {
                    if (message.sequence() != connection.sentSequence + 1) {
                        close(connection, CloseStatus.SESSION_NOT_RELIABLE);
                        return;
                    }
                    if (!send(connection, new MessageCreated("MESSAGE_CREATED", message))) return;
                    // 소켓 쓰기 완료 위치이며 클라이언트의 수신/읽음 확인으로 간주하지 않음.
                    connection.sentSequence = message.sequence();
                }
                state = page.room();
                if (page.hasNext()) {
                    // 한 연결이 작업 스레드를 독점하지 않도록 한 페이지 후 대기열 뒤로 보냄.
                    connection.pending.set(true);
                    return;
                }
            }
            // 본문보다 상태를 먼저 알리면 프론트가 불필요한 HTTP 복구를 시작하므로 마지막에 보냄.
            send(connection, new Update("ROOM_UPDATED", state));
        } catch (BusinessException e) {
            close(connection, CloseStatus.POLICY_VIOLATION);
        } catch (IOException e) {
            close(connection, CloseStatus.SESSION_NOT_RELIABLE);
        } catch (Exception e) {
            log.warn("채팅 소켓 동기화 실패: roomId={}", connection.subscription.roomId());
            close(connection, CloseStatus.SERVER_ERROR);
        } finally {
            connection.running.set(false);
            if (connection.pending.get()) requestSync(connection);
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
        volatile Subscription subscription;
        volatile long sendStartedAt = -1;
        boolean ready;
        long sentSequence;
        Connection(WebSocketSession session, long openedAt) { this.session = session; this.openedAt = openedAt; }
    }
    private record Subscription(Long userId, Long roomId, String accessToken, boolean includeMessages) {}
    public record Subscribe(String type, Long roomId, String accessToken, Boolean includeMessages) {}
    public record Ready(String type, ChatRoomResponse room, boolean includeMessages) {}
    public record Update(String type, ChatRoomResponse room) {}
    public record MessageCreated(String type, ChatMessageResponse message) {}
}
