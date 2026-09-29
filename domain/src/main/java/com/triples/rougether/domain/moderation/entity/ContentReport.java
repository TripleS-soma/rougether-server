package com.triples.rougether.domain.moderation.entity;

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

// 콘텐츠 신고(#399). 사용자·대상당 1건(unique)이라 재신고는 기존 row 를 돌려줌.
// 대상은 논리 참조(FK 없음). targetOwnerUserId 는 신고 시점 작성자·제작자 스냅샷.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "content_reports")
public class ContentReport {

    public static final int DETAIL_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reporter_user_id", nullable = false, updatable = false)
    private Long reporterUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, updatable = false, length = 20)
    private ContentReportTargetType targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    @Column(name = "target_owner_user_id", updatable = false)
    private Long targetOwnerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false, length = 20)
    private ContentReportReason reason;

    @Column(name = "detail", length = DETAIL_MAX_LENGTH, updatable = false)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private ContentReportStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    public static ContentReport receive(Long reporterUserId, ContentReportTargetType targetType, Long targetId,
                                        Long targetOwnerUserId, ContentReportReason reason, String detail,
                                        Instant now) {
        ContentReport report = new ContentReport();
        report.reporterUserId = reporterUserId;
        report.targetType = targetType;
        report.targetId = targetId;
        report.targetOwnerUserId = targetOwnerUserId;
        report.reason = reason;
        report.detail = detail;
        report.status = ContentReportStatus.RECEIVED;
        report.createdAt = now;
        return report;
    }

    public boolean isReceived() {
        return status == ContentReportStatus.RECEIVED;
    }
}
