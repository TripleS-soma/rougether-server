package com.triples.rougether.domain.notification.digest.entity;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.PushStatus;
import com.triples.rougether.domain.support.BaseEntity;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "daily_incomplete_digests")
public class DailyIncompleteDigest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "digest_date", nullable = false)
    private LocalDate digestDate;

    @Column(name = "routine_count", nullable = false)
    private int routineCount;

    @Column(name = "todo_count", nullable = false)
    private int todoCount;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_id")
    private Notification notification;

    @Enumerated(EnumType.STRING)
    @Column(name = "push_status", length = 20, nullable = false)
    private PushStatus pushStatus;

    @Column(name = "sent_at")
    private Instant sentAt;

    private DailyIncompleteDigest(User user, LocalDate digestDate, int routineCount, int todoCount) {
        this.user = user;
        this.digestDate = digestDate;
        this.routineCount = routineCount;
        this.todoCount = todoCount;
        this.pushStatus = PushStatus.PENDING;
    }

    public static DailyIncompleteDigest create(User user, LocalDate digestDate, int routineCount, int todoCount) {
        if (routineCount < 0 || todoCount < 0 || routineCount + todoCount == 0) {
            throw new IllegalArgumentException("미완료 digest 는 최소 1개 이상의 target 이 필요함");
        }
        return new DailyIncompleteDigest(user, digestDate, routineCount, todoCount);
    }

    public void linkNotification(Notification notification) {
        if (notification == null) {
            throw new IllegalArgumentException("notification 은 null 일 수 없음");
        }
        this.notification = notification;
    }

    public void updatePushStatus(PushStatus pushStatus, Instant now) {
        this.pushStatus = pushStatus;
        if (pushStatus == PushStatus.SENT) {
            this.sentAt = now;
        }
    }
}
