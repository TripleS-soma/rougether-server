package com.triples.rougether.domain.chat.entity;

import com.triples.rougether.domain.member.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 20)
    private ChatMessageType messageType = ChatMessageType.TEXT;
    @Column(name = "deleted_at")
    private Instant deletedAt;

    // 사진만 보낸 메시지의 content. 구버전 앱은 이 문구를 말풍선으로 표시함.
    public static final String IMAGE_FALLBACK_CONTENT = "사진을 보냈어요";

    public static ChatMessage sendImage(ChatRoom room, User sender, String clientMessageId, Instant now) {
        ChatMessage message = send(room, sender, clientMessageId, IMAGE_FALLBACK_CONTENT, now);
        message.messageType = ChatMessageType.IMAGE;
        return message;
    }

    public boolean isImage() { return messageType == ChatMessageType.IMAGE; }

    // 운영자 삭제 후 표시 문구. 원문은 신고 확인을 위해 DB에 남겨 둠.
    public static final String DELETED_CONTENT = "삭제된 메시지예요";

    public void delete(Instant now) {
        if (deletedAt == null) deletedAt = now;
    }

    public boolean isDeleted() { return deletedAt != null; }

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
