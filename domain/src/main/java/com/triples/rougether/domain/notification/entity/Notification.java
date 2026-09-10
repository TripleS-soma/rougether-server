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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

// 알림 내역. NotificationService.send(...)가 push 성패와 무관하게 항상 저장함(best-effort push).
// @DynamicUpdate 필수 — 전체 컬럼 UPDATE면 삭제(deleted_at)와 거의 동시에 진행되던 push 상태 갱신·읽음 처리의
// stale flush 가 deleted_at=null 을 되써서 soft delete 를 되돌리고, 반대로 사용자 요청 flush 가 batch 가 bulk 로
// 갱신한 push_status 를 PENDING 으로 되돌려 재발송시킬 수 있음(dirty 필드만 갱신해 차단. User 와 같은 이유).
@DynamicUpdate
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

    // 알림함 삭제 시각(soft delete). 목록·전체 읽음만 null 인 행을 보고, 단건 읽음·삭제는 삭제 여부 무관(멱등).
    // 발송·중복 발송 판정·digest FK 는 삭제 여부를 보지 않음(행 보존이 목적) - 삭제된 PENDING 은 BLOCKED 로 종결.
    @Column(name = "deleted_at")
    private Instant deletedAt;

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

    // 알림함에서 삭제. 이미 삭제된 행은 최초 삭제 시각을 유지함.
    public void softDelete(Instant now) {
        if (deletedAt == null) {
            this.deletedAt = now;
        }
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
