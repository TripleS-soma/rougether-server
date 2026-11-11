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

// 집 채팅 사진. message_id가 비어 있으면 아직 전송하지 않은 업로드이고 expires_at 이후 정리됨.
@Getter
@Entity
@Table(name = "chat_images")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatImage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "room_id", nullable = false)
    private Long roomId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;
    @Column(name = "message_id")
    private Long messageId;
    @Column(name = "storage_key", nullable = false)
    private String storageKey;
    @Column(nullable = false)
    private int width;
    @Column(nullable = false)
    private int height;
    @Column(nullable = false)
    private boolean ready;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static ChatImage reserve(Long roomId, User owner, String storageKey, int width, int height,
                                    Instant now, Instant expiresAt) {
        ChatImage image = new ChatImage();
        image.roomId = roomId;
        image.owner = owner;
        image.storageKey = storageKey;
        image.width = width;
        image.height = height;
        image.createdAt = now;
        image.expiresAt = expiresAt;
        return image;
    }

    public void complete() { ready = true; }

    public void expire(Instant now) { expiresAt = now; }

    public void attach(Long messageId) { this.messageId = messageId; }

    public boolean isAttachableBy(Long userId, Long roomId, Instant now) {
        return owner.getId().equals(userId) && this.roomId.equals(roomId) && ready && messageId == null
                && expiresAt.isAfter(now);
    }
}
