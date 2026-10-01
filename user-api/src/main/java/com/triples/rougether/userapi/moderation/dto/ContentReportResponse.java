package com.triples.rougether.userapi.moderation.dto;

import com.triples.rougether.domain.moderation.entity.ContentReport;
import com.triples.rougether.domain.moderation.entity.ContentReportStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record ContentReportResponse(
        @Schema(description = "신고 ID. 같은 대상을 다시 신고하면 처음 신고 ID", example = "12")
        Long reportId,
        @Schema(description = "처리 상태. 허용값: RECEIVED(검토 대기), ACTIONED(숨김 조치), DISMISSED(조치 없음 종료)",
                example = "RECEIVED")
        ContentReportStatus status) {

    public static ContentReportResponse of(ContentReport report) {
        return new ContentReportResponse(report.getId(), report.getStatus());
    }
}
