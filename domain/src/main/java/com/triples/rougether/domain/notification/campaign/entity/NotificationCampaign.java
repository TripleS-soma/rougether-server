package com.triples.rougether.domain.notification.campaign.entity;

import com.triples.rougether.domain.admin.entity.AdminUser;
import com.triples.rougether.domain.support.BaseCreatedEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "notification_campaigns", uniqueConstraints =
        @UniqueConstraint(name = "uk_notification_campaign_request", columnNames = {"created_by_admin_id", "request_id"}))
public class NotificationCampaign extends BaseCreatedEntity {
    public enum Audience { ALL, SELECTED }
    public enum Status { DRAFT, SENDING, COMPLETED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_admin_id", nullable = false)
    private AdminUser createdBy;
    @Column(name = "request_id", length = 36, nullable = false)
    private String requestId;
    @Column(name = "request_fingerprint", length = 64, nullable = false)
    private String requestFingerprint;
    @Column(length = 255, nullable = false)
    private String title;
    @Column(length = 1000, nullable = false)
    private String body;
    @Enumerated(EnumType.STRING) @Column(length = 20, nullable = false)
    private Audience audience;
    @Column(name = "push_enabled", nullable = false)
    private boolean pushEnabled;
    @Enumerated(EnumType.STRING) @Column(length = 20, nullable = false)
    private Status status;
    @Column(name = "recipient_count", nullable = false)
    private int recipientCount;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "approved_by_username", length = 50)
    private String approvedByUsername;
    @Column(name = "completed_at")
    private Instant completedAt;

    public static NotificationCampaign draft(AdminUser admin, String requestId, String fingerprint,
                                               String title, String body, Audience audience,
                                               boolean pushEnabled, int recipientCount) {
        NotificationCampaign campaign = new NotificationCampaign();
        campaign.createdBy = admin;
        campaign.requestId = requestId;
        campaign.requestFingerprint = fingerprint;
        campaign.title = title;
        campaign.body = body;
        campaign.audience = audience;
        campaign.pushEnabled = pushEnabled;
        campaign.recipientCount = recipientCount;
        campaign.status = Status.DRAFT;
        return campaign;
    }

    public void start(Instant now, String approvedByUsername) {
        if (status == Status.DRAFT) {
            status = Status.SENDING;
            startedAt = now;
            this.approvedByUsername = approvedByUsername;
        }
    }

    public void complete(Instant now) {
        if (status == Status.SENDING) {
            status = Status.COMPLETED;
            completedAt = now;
        }
    }
}
