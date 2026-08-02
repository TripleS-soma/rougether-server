package com.triples.rougether.domain.bugreport.entity;

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

// 버그 제보 스크린샷 1장 (#213). storage_key 는 S3 bug-reports/ prefix 의 object key.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "bug_report_images")
public class BugReportImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bug_report_id", nullable = false)
    private BugReport bugReport;

    @Column(name = "storage_key", length = 255, nullable = false)
    private String storageKey;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public static BugReportImage of(BugReport bugReport, String storageKey, int sortOrder) {
        BugReportImage image = new BugReportImage();
        image.bugReport = bugReport;
        image.storageKey = storageKey;
        image.sortOrder = sortOrder;
        return image;
    }
}
