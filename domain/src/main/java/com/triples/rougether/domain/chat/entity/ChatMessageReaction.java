package com.triples.rougether.domain.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 메시지 공감. (message_id, user_id)가 유일해 한 사람이 메시지당 하나만 가짐.
@Getter
@Entity
@Table(name = "chat_message_reactions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessageReaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "message_id", nullable = false)
    private Long messageId;
    @Column(name = "room_id", nullable = false)
    private Long roomId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Enumerated(EnumType.STRING)
    @Column(name = "reaction_type", nullable = false, length = 20)
    private ChatReactionType reactionType;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static ChatMessageReaction of(ChatMessage message, Long userId, ChatReactionType type, Instant now) {
        ChatMessageReaction reaction = new ChatMessageReaction();
        reaction.messageId = message.getId();
        reaction.roomId = message.getRoomId();
        reaction.userId = userId;
        reaction.reactionType = type;
        reaction.createdAt = now;
        return reaction;
    }

    // 유형을 바꾸면 새로 누른 것으로 보고 누른 순서의 맨 뒤로 보냄. 같은 유형이면 바꾸지 않음.
    public boolean change(ChatReactionType type, Instant now) {
        if (reactionType == type) return false;
        reactionType = type;
        createdAt = now;
        return true;
    }
}
