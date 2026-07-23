package com.triples.rougether.userapi.bugreport.dto;

import com.triples.rougether.domain.bugreport.entity.BugReport;
import com.triples.rougether.domain.bugreport.entity.BugReportStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

// POST /api/v1/bug-reports 응답 및 GET /api/v1/me/bug-reports 의 항목.
public record BugReportResponse(
        @Schema(description = "제보 ID", example = "7")
        Long bugReportId,
        @Schema(description = "제목", example = "루틴 완료가 안 눌려요")
        String title,
        @Schema(description = "내용", example = "오늘 탭에서 완료 버튼을 눌러도 반응이 없습니다.")
        String content,
        @Schema(description = "처리 상태. RECEIVED(접수), IN_PROGRESS(확인 중), RESOLVED(완료)", example = "RECEIVED")
        BugReportStatus status,
        @Schema(description = "스크린샷 storage key 목록 (CDN base URL 과 조합해 이미지 URL 로 사용, 최대 3장)")
        List<String> screenshotKeys,
        @Schema(description = "제출 시각")
        Instant createdAt) {

    public static BugReportResponse of(BugReport report, List<String> screenshotKeys) {
        return new BugReportResponse(
                report.getId(),
                report.getTitle(),
                report.getContent(),
                report.getStatus(),
                screenshotKeys,
                report.getCreatedAt());
    }
}
