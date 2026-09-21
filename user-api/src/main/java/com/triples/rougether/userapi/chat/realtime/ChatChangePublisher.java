package com.triples.rougether.userapi.chat.realtime;

import com.triples.rougether.userapi.chat.service.ChatRoomChanged;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
public class ChatChangePublisher {
    private final ChatSocketHandler sockets;
    private final StringRedisTemplate redis;

    public ChatChangePublisher(ChatSocketHandler sockets,
            @Qualifier("chatRedis") ObjectProvider<StringRedisTemplate> redis) {
        this.sockets = sockets;
        this.redis = redis.getIfAvailable();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void committed(ChatRoomChanged event) {
        sockets.changed(event.roomId());
        if (redis == null) return;
        try {
            redis.convertAndSend(ChatRedisConfig.CHANNEL, event.roomId().toString());
        } catch (RuntimeException e) {
            // 저장 성공을 발행 실패로 뒤집지 않음. 다른 노드도 5초 동기화로 DB 정본을 복구함.
            log.warn("채팅 변경 알림 발행 실패: roomId={}", event.roomId());
        }
    }
}
