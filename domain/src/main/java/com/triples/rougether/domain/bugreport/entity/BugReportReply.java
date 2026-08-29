package com.triples.rougether.domain.bugreport.entity;

import com.triples.rougether.domain.admin.entity.AdminUser;
import com.triples.rougether.domain.support.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

// 버그 제보에 대한 운영자 답장 1건 (#348). 한 제보에 여러 개 append-only, 수정·삭제 없음.
// 작성 어드민(admin_user_id)은 감사용 - 유저 응답에는 노출하지 않는다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "bug_report_replies")
public class BugReportReply extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bug_report_id", nullable = false)
    private BugReport bugReport;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admin_user_id", nullable = false)
    private AdminUser adminUser;

    @Column(name = "content", length = 2000, nullable = false)
    private String content;

    public static BugReportReply of(BugReport bugReport, AdminUser adminUser, String content) {
        BugReportReply reply = new BugReportReply();
        reply.bugReport = bugReport;
        reply.adminUser = adminUser;
        reply.content = content;
        return reply;
    }
}
