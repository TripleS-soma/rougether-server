package com.triples.rougether.userapi.chat.realtime;

import com.triples.rougether.userapi.chat.service.ChatReactionChanged;
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

    // 공감 변경은 전용 채널로 알림. 기존 채널은 방 ID만 싣는 형식이라 배포 전환 중 구버전 노드가 읽을 수 있게 그대로 둠.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void reactionCommitted(ChatReactionChanged event) {
        sockets.reactionsChanged(event.roomId(), event.messageId());
        if (redis == null) return;
        try {
            redis.convertAndSend(ChatRedisConfig.REACTION_CHANNEL, event.roomId() + ":" + event.messageId());
        } catch (RuntimeException e) {
            // 공감 저장은 이미 끝났음. 다른 노드의 화면은 기록을 다시 조회할 때 맞춰짐.
            log.warn("채팅 공감 알림 발행 실패: roomId={}", event.roomId());
        }
    }
}
