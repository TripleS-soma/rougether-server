package com.triples.rougether.userapi.chat.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.chat.entity.ChatMessage;
import com.triples.rougether.domain.chat.entity.ChatMessageReaction;
import com.triples.rougether.domain.chat.entity.ChatReactionType;
import com.triples.rougether.domain.chat.repository.ChatMessageReactionRepository;
import com.triples.rougether.domain.chat.repository.ChatMessageRepository;
import com.triples.rougether.userapi.chat.dto.ChatReactionsResponse;
import com.triples.rougether.userapi.chat.error.ChatErrorCode;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 메시지 공감 설정·취소. 읽음 상태와 sequence는 건드리지 않고 푸시도 보내지 않음.
// lockWriter의 첫 조회가 잠금 없는 조회라 READ COMMITTED여야 회원 행 잠금 뒤에 앞선 요청의 공감이 보임.
@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ChatReactionService {
    private final ChatAccessPolicy access;
    private final ChatMessageRepository messages;
    private final ChatMessageReactionRepository reactions;
    private final ChatQueryService query;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // 내 공감을 설정하거나 다른 유형으로 바꿈. 같은 유형을 다시 보내면 그대로 둠.
    // 회원 행 잠금(lockWriter)이 같은 사람의 동시 요청을 직렬화해 (message_id, user_id) 중복 삽입이 생기지 않음.
    public ChatReactionsResponse react(Long userId, Long roomId, Long messageId, ChatReactionType type) {
        if (type == null) throw new BusinessException(ChatErrorCode.CHAT_INPUT_INVALID);
        access.lockWriter(roomId, userId);
        ChatMessage message = reactable(roomId, messageId);
        Instant now = clock.instant();
        boolean changed = reactions.findByMessageIdAndUserId(messageId, userId)
                .map(existing -> existing.change(type, now))
                .orElseGet(() -> {
                    reactions.save(ChatMessageReaction.of(message, userId, type, now));
                    return true;
                });
        return result(message, changed);
    }

    // 내 공감을 취소함. 공감이 없어도 성공함.
    public ChatReactionsResponse remove(Long userId, Long roomId, Long messageId) {
        access.lockWriter(roomId, userId);
        ChatMessage message = reactable(roomId, messageId);
        var existing = reactions.findByMessageIdAndUserId(messageId, userId);
        existing.ifPresent(reactions::delete);
        return result(message, existing.isPresent());
    }

    // 없는 메시지, 다른 방의 메시지, 운영자가 삭제한 메시지는 구분하지 않고 404로 응답함.
    private ChatMessage reactable(Long roomId, Long messageId) {
        return messages.findById(messageId)
                .filter(found -> found.getRoomId().equals(roomId) && !found.isDeleted())
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_MESSAGE_NOT_FOUND));
    }

    private ChatReactionsResponse result(ChatMessage message, boolean changed) {
        if (changed) events.publishEvent(new ChatReactionChanged(message.getRoomId(), message.getId()));
        return query.reactionsOf(message);
    }
}
