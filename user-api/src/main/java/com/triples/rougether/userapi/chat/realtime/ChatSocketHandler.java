package com.triples.rougether.userapi.chat.realtime;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.chat.dto.ChatRoomResponse;
import com.triples.rougether.userapi.chat.service.ChatQueryService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
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

// 쓰기는 인증된 HTTP API가 담당하고 소켓은 최신 방 상태를 알림. 본문은 순서 커서로 복구함.
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatSocketHandler extends TextWebSocketHandler {
    private final ChatQueryService query;
    private final TokenService tokens;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Set<Long> dirtyRooms = ConcurrentHashMap.newKeySet();
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
                new ConcurrentWebSocketSessionDecorator(session, 3000, 16384), clock.millis()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Connection connection = connections.get(session.getId());
        if (connection == null) return;
        try {
            if (connection.subscription != null || message.getPayloadLength() > 8192
                    || clock.millis() - connection.openedAt >= 10000) {
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
            ChatRoomResponse state = query.get(userId, request.roomId());
            synchronized (this) {
                long count = connections.values().stream().filter(c -> c.subscription != null
                        && c.subscription.userId().equals(userId)).count();
                if (count >= 10) {
                    close(connection, CloseStatus.POLICY_VIOLATION);
                    return;
                }
                send(connection, "READY", state);
                connection.subscription = new Subscription(userId, request.roomId(), request.accessToken());
            }
        } catch (BusinessException | IllegalArgumentException e) {
            close(connection, CloseStatus.POLICY_VIOLATION);
        } catch (tools.jackson.core.JacksonException e) {
            close(connection, CloseStatus.BAD_DATA);
        } catch (Exception e) {
            close(connection, CloseStatus.SERVER_ERROR);
        }
    }

    public void changed(Long roomId) {
        // 구독자가 없는 방 알림을 쌓지 않음.
        if (connections.values().stream().anyMatch(c -> c.subscription != null
                && c.subscription.roomId().equals(roomId))) dirtyRooms.add(roomId);
    }

    // 전용 스케줄러를 사용해 다른 도메인의 배치와 소켓 IO를 분리함.
    @Scheduled(fixedDelay = 250, scheduler = "chatScheduler")
    public void flush() {
        long now = clock.millis();
        boolean reconcile = now >= nextReconcile;
        if (reconcile) nextReconcile = now + 5000;
        Map<Long, List<Connection>> byRoom = new HashMap<>();
        for (Connection connection : connections.values()) {
            Subscription subscription = connection.subscription;
            if (subscription == null) {
                if (now - connection.openedAt >= 10000) close(connection, CloseStatus.POLICY_VIOLATION);
            } else {
                byRoom.computeIfAbsent(subscription.roomId(), ignored -> new ArrayList<>()).add(connection);
            }
        }
        dirtyRooms.retainAll(byRoom.keySet());
        for (var entry : byRoom.entrySet()) {
            boolean changed = dirtyRooms.remove(entry.getKey());
            if (!reconcile && !changed) continue;
            try {
                // 방마다 한 번 읽고 현재 구성원에게만 보냄. 탈퇴·강퇴·삭제·봇은 수신 불가함.
                ChatRoomResponse state = query.liveSnapshot(entry.getKey());
                for (Connection connection : entry.getValue()) {
                    try {
                        var subscription = connection.subscription;
                        tokens.parseAccessToken(subscription.accessToken());
                        if (!state.includes(subscription.userId())) close(connection, CloseStatus.POLICY_VIOLATION);
                        else send(connection, "ROOM_UPDATED", state);
                    } catch (BusinessException e) {
                        close(connection, CloseStatus.POLICY_VIOLATION);
                    }
                }
            } catch (BusinessException e) {
                entry.getValue().forEach(c -> close(c, CloseStatus.POLICY_VIOLATION));
            } catch (Exception e) {
                // DB/IO 장애 시 내용을 보내지 않고 다음 동기화에서 재시도함.
                dirtyRooms.add(entry.getKey());
                log.warn("채팅 상태 동기화 실패: roomId={}", entry.getKey());
            }
        }
    }

    private void send(Connection connection, String type, ChatRoomResponse room) {
        // 느린 소켓이 상태 조회/다른 방 수신을 막지 않게 송신 작업을 유한 큐로 분리함.
        // 중간 상태가 합쳐져도 다음 5초 동기화와 메시지 커서 조회로 복구할 수 있음.
        if (!connection.sending.compareAndSet(false, true)) return;
        try {
            outbound.execute(() -> {
                try {
                    if (connection.session.isOpen()) connection.session.sendMessage(
                            new TextMessage(mapper.writeValueAsString(new Update(type, room))));
                    else connections.remove(connection.session.getId());
                } catch (Exception e) {
                    close(connection, CloseStatus.SESSION_NOT_RELIABLE);
                } finally {
                    connection.sending.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            connection.sending.set(false);
            close(connection, CloseStatus.SERVICE_OVERLOAD);
        }
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
        connections.remove(connection.session.getId());
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
        volatile Subscription subscription;
        final AtomicBoolean sending = new AtomicBoolean();
        Connection(WebSocketSession session, long openedAt) { this.session = session; this.openedAt = openedAt; }
    }
    private record Subscription(Long userId, Long roomId, String accessToken) {}
    public record Subscribe(String type, Long roomId, String accessToken) {}
    public record Update(String type, ChatRoomResponse room) {}
}
