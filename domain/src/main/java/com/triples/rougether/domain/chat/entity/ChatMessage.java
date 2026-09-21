package com.triples.rougether.domain.chat.entity;

import com.triples.rougether.domain.member.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "chat_messages")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "room_id", nullable = false)
    private Long roomId;
    @Column(name = "message_sequence", nullable = false)
    private long sequence;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_user_id", nullable = false)
    private User sender;
    @Column(name = "client_message_id", nullable = false, length = 36)
    private String clientMessageId;
    @Column(nullable = false, length = 2000)
    private String content;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static ChatMessage send(ChatRoom room, User sender, String clientMessageId,
                                   String content, Instant now) {
        ChatMessage message = new ChatMessage();
        message.roomId = room.getId();
        message.sequence = room.nextSequence();
        message.sender = sender;
        message.clientMessageId = clientMessageId;
        message.content = content;
        message.createdAt = now;
        return message;
    }
}
