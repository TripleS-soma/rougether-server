package com.triples.rougether.adminapi.bugreport.dto;

import com.triples.rougether.domain.bugreport.entity.BugReport;
import com.triples.rougether.domain.bugreport.entity.BugReportStatus;
import java.time.Instant;
import java.util.List;

// 어드민 목록·상태 변경 응답의 제보 1건. 제보자 식별을 위해 userId·nickname 포함.
public record AdminBugReportResponse(
        Long bugReportId,
        Long userId,
        String nickname,
        String title,
        String content,
        String appVersion,
        String deviceInfo,
        BugReportStatus status,
        List<String> screenshotKeys,
        Instant createdAt) {

    public static AdminBugReportResponse of(BugReport report, List<String> screenshotKeys) {
        return new AdminBugReportResponse(
                report.getId(),
                report.getUser().getId(),
                report.getUser().getNickname(),
                report.getTitle(),
                report.getContent(),
                report.getAppVersion(),
                report.getDeviceInfo(),
                report.getStatus(),
                screenshotKeys,
                report.getCreatedAt());
    }
}
