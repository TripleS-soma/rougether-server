package com.triples.rougether.domain.notification.campaign.entity;

import com.triples.rougether.domain.support.BaseCreatedEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "notification_campaign_recipients", uniqueConstraints = {
        @UniqueConstraint(name = "uk_notification_campaign_user", columnNames = {"campaign_id", "user_id"}),
        @UniqueConstraint(name = "uk_notification_campaign_notification", columnNames = "notification_id")})
public class NotificationCampaignRecipient extends BaseCreatedEntity {
    public enum Status { PENDING, CREATED, SKIPPED, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "campaign_id", nullable = false)
    private NotificationCampaign campaign;
    // 대상 스냅샷은 회원 탈퇴 뒤에도 감사 이력으로 유지함.
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "notification_id")
    private Long notificationId;
    @Enumerated(EnumType.STRING) @Column(length = 20, nullable = false)
    private Status status;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    public static NotificationCampaignRecipient snapshot(NotificationCampaign campaign, Long userId) {
        NotificationCampaignRecipient recipient = new NotificationCampaignRecipient();
        recipient.campaign = campaign;
        recipient.userId = userId;
        recipient.status = Status.PENDING;
        return recipient;
    }

    public void created(Long notificationId) {
        this.notificationId = notificationId;
        status = Status.CREATED;
    }

    public void skip() { status = Status.SKIPPED; }

    public void failAttempt() {
        attemptCount++;
        if (attemptCount >= 3) {
            status = Status.FAILED;
        }
    }
}
