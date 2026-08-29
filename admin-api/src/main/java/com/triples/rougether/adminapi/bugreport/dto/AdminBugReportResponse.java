package com.triples.rougether.adminapi.bugreport.dto;

import com.triples.rougether.domain.bugreport.entity.BugReport;
import com.triples.rougether.domain.bugreport.entity.BugReportReply;
import com.triples.rougether.domain.bugreport.entity.BugReportStatus;
import java.time.Instant;
import java.util.List;

// 어드민 목록·상태 변경·답장 응답의 제보 1건. 제보자 식별을 위해 userId·nickname 포함.
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
        List<AdminBugReportReplyResponse> replies,
        Instant createdAt) {

    public static AdminBugReportResponse of(BugReport report, List<String> screenshotKeys,
                                            List<AdminBugReportReplyResponse> replies) {
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
                replies,
                report.getCreatedAt());
    }

    // 운영자 답장 1건 (#348). 작성 어드민 표시는 어드민 화면 전용 - 유저 응답에는 노출하지 않음.
    public record AdminBugReportReplyResponse(Long replyId, String adminUsername,
                                              String content, Instant createdAt) {

        public static AdminBugReportReplyResponse of(BugReportReply reply) {
            return new AdminBugReportReplyResponse(
                    reply.getId(),
                    reply.getAdminUser().getUsername(),
                    reply.getContent(),
                    reply.getCreatedAt());
        }
    }
}
