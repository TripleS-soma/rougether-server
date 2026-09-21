package com.triples.rougether.userapi.chat.realtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.triples.rougether.common.error.AuthErrorCode;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.chat.entity.ChatRoomType;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.chat.dto.ChatRoomResponse;
import com.triples.rougether.userapi.chat.service.ChatQueryService;
import com.triples.rougether.userapi.chat.service.ChatRoomChanged;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.MemberRole;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.*;
import tools.jackson.databind.json.JsonMapper;

class ChatRealtimeTest {
    ChatRoomResponse state = new ChatRoomResponse(3L, ChatRoomType.HOUSE, 2L, 0,
            List.of(new ChatRoomResponse.Reader(1L, 11L, 0)));

    @Test
    void 인증하지_않은_연결은_10초_후_종료되고_방_정보를_받지_못한다() throws Exception {
        var query = mock(ChatQueryService.class);
        var clock = mock(Clock.class);
        var handler = new ChatSocketHandler(query, mock(TokenService.class), JsonMapper.builder().build(), clock);
        var socket = session("anonymous");
        try {
            handler.afterConnectionEstablished(socket);
            when(clock.millis()).thenReturn(10000L);
            handler.flush();
            verify(socket).close(CloseStatus.POLICY_VIOLATION);
            verifyNoInteractions(query);
            verify(socket, never()).sendMessage(any());
        } finally { handler.shutdown(); }
    }

    @Test
    void 연결_후_만료된_JWT는_다음_수신_전에_차단한다() throws Exception {
        var query = mock(ChatQueryService.class);
        var token = mock(TokenService.class);
        var clock = mock(Clock.class);
        when(token.parseAccessToken("valid-token")).thenReturn(new AuthUser(1L, MemberRole.NORMAL));
        when(query.get(1L, 3L)).thenReturn(state);
        when(query.liveSnapshot(3L)).thenReturn(state);
        var handler = new ChatSocketHandler(query, token, JsonMapper.builder().build(), clock);
        var socket = session("expiring");
        try {
            handler.afterConnectionEstablished(socket);
            handler.handleTextMessage(socket, new TextMessage(
                    "{\"type\":\"SUBSCRIBE\",\"roomId\":3,\"accessToken\":\"valid-token\"}"));
            verify(socket, timeout(1000)).sendMessage(any(TextMessage.class));
            when(token.parseAccessToken("valid-token")).thenThrow(new BusinessException(AuthErrorCode.INVALID_TOKEN));
            handler.flush();
            verify(socket).close(CloseStatus.POLICY_VIOLATION);
            verify(socket, times(1)).sendMessage(any());
        } finally { handler.shutdown(); }
    }

    @Test
    void Redis_장애에도_저장_성공을_실패로_바꾸지_않는다() {
        var socket = mock(ChatSocketHandler.class);
        var redis = mock(StringRedisTemplate.class);
        when(redis.convertAndSend(anyString(), any())).thenThrow(new IllegalStateException("offline"));
        var factory = new StaticListableBeanFactory();
        factory.addBean("chatRedis", redis);
        var publisher = new ChatChangePublisher(socket, factory.getBeanProvider(StringRedisTemplate.class));
        assertThatCode(() -> publisher.committed(new ChatRoomChanged(3L))).doesNotThrowAnyException();
        verify(socket).changed(3L);
    }

    @Test
    void Redis_알림이_없어도_정기_동기화로_누락된_상태를_회복한다() throws Exception {
        var query = mock(ChatQueryService.class);
        var token = mock(TokenService.class);
        var clock = mock(Clock.class);
        when(token.parseAccessToken("token")).thenReturn(new AuthUser(1L, MemberRole.NORMAL));
        when(query.get(1L, 3L)).thenReturn(state);
        when(query.liveSnapshot(3L)).thenReturn(new ChatRoomResponse(3L, ChatRoomType.HOUSE, 2L, 9, state.readers()));
        var handler = new ChatSocketHandler(query, token, JsonMapper.builder().build(), clock);
        var socket = session("recover");
        try {
            handler.afterConnectionEstablished(socket);
            handler.handleTextMessage(socket, new TextMessage(
                    "{\"type\":\"SUBSCRIBE\",\"roomId\":3,\"accessToken\":\"token\"}"));
            verify(socket, timeout(1000)).sendMessage(any());
            when(clock.millis()).thenReturn(5000L);
            handler.flush();
            verify(socket, timeout(1000)).sendMessage(argThat(message -> message.getPayload().toString().contains("\"lastSequence\":9")));
        } finally { handler.shutdown(); }
    }

    private WebSocketSession session(String id) {
        var socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(id);
        when(socket.isOpen()).thenReturn(true);
        return socket;
    }
}
