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

    // 사진 메시지의 설명 글 원문. 설명 글이 없으면 null임.
    @Column(length = 2000)
    private String caption;
    // 이모티콘 메시지의 key. 그림은 앱 번들에 있고 서버는 key만 보관함.
    @Column(name = "emoticon_key", length = 64)
    private String emoticonKey;

    // 설명 글 없이 사진만 보낸 메시지의 content. 구버전 앱은 이 문구를 말풍선으로 표시함.
    public static final String IMAGE_FALLBACK_CONTENT = "사진을 보냈어요";
    public static final String EMOTICON_FALLBACK_CONTENT = "이모티콘을 보냈어요";

    public static String imageFallbackContent(int imageCount) {
        return imageCount <= 1 ? IMAGE_FALLBACK_CONTENT : "사진 " + imageCount + "장을 보냈어요";
    }

    // content는 구버전 앱 표시용 문구임. 설명 글이 있으면 그 글, 없으면 장수에 맞는 안내 문구를 넣음.
    public static ChatMessage sendImage(ChatRoom room, User sender, String clientMessageId, String caption,
                                        int imageCount, Instant now) {
        ChatMessage message = send(room, sender, clientMessageId,
                caption != null ? caption : imageFallbackContent(imageCount), now);
        message.messageType = ChatMessageType.IMAGE;
        message.caption = caption;
        return message;
    }

    public static ChatMessage sendEmoticon(ChatRoom room, User sender, String clientMessageId, String emoticonKey,
                                           Instant now) {
        ChatMessage message = send(room, sender, clientMessageId, EMOTICON_FALLBACK_CONTENT, now);
        message.messageType = ChatMessageType.EMOTICON;
        message.emoticonKey = emoticonKey;
        return message;
    }

    public boolean isImage() { return messageType == ChatMessageType.IMAGE; }

    public boolean isText() { return messageType == ChatMessageType.TEXT; }

    public boolean isEmoticon() { return messageType == ChatMessageType.EMOTICON; }

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
