package com.triples.rougether.domain.notification.entity;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.support.BaseCreatedEntity;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 알림 내역. NotificationService.send(...)가 push 성패와 무관하게 항상 저장함(best-effort push).
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "notification")
public class Notification extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 30, nullable = false)
    private NotificationType type;

    @Column(name = "title", length = 255, nullable = false)
    private String title;

    @Column(name = "body", length = 1000, nullable = false)
    private String body;

    // 발송 원인 리소스 ID(예: 리마인드면 routineId). 중복 발송 판정용, 원인 없는 알림은 null.
    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "is_read", nullable = false)
    private boolean isRead;

    @Enumerated(EnumType.STRING)
    @Column(name = "push_status", length = 20, nullable = false)
    private PushStatus pushStatus;

    private Notification(User user, NotificationType type, String title, String body, Long refId) {
        this.user = user;
        this.type = type;
        this.title = title;
        this.body = body;
        this.refId = refId;
        this.isRead = false;
        this.pushStatus = PushStatus.PENDING;
    }

    public static Notification create(User user, NotificationType type, String title, String body, Long refId) {
        return new Notification(user, type, title, body, refId);
    }

    public void markRead() {
        this.isRead = true;
    }

    public void markPushSent() {
        this.pushStatus = PushStatus.SENT;
    }

    public void markPushFailed() {
        this.pushStatus = PushStatus.FAILED;
    }

    public void markPushBlocked() {
        this.pushStatus = PushStatus.BLOCKED;
    }
}
