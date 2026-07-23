package com.triples.rougether.domain.bugreport.entity;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.support.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

// 버그 제보 1건 (#213). 상태 전이는 어드민만 수행하며 순서 강제 없음.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "bug_reports")
@EntityListeners(AuditingEntityListener.class)
public class BugReport extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "title", length = 100, nullable = false)
    private String title;

    @Column(name = "content", length = 2000, nullable = false)
    private String content;

    @Column(name = "app_version", length = 30)
    private String appVersion;

    @Column(name = "device_info", length = 100)
    private String deviceInfo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private BugReportStatus status;

    public static BugReport submit(User user, String title, String content,
                                   String appVersion, String deviceInfo) {
        BugReport report = new BugReport();
        report.user = user;
        report.title = title;
        report.content = content;
        report.appVersion = appVersion;
        report.deviceInfo = deviceInfo;
        report.status = BugReportStatus.RECEIVED;
        return report;
    }

    public void changeStatus(BugReportStatus status) {
        this.status = status;
    }
}
