package com.triples.rougether.adminapi.moderation.dto;

import com.triples.rougether.domain.moderation.entity.ContentReport;
import com.triples.rougether.domain.moderation.entity.ContentReportReason;
import com.triples.rougether.domain.moderation.entity.ContentReportStatus;
import com.triples.rougether.domain.moderation.entity.ContentReportTargetType;
import java.time.Instant;
import java.util.List;

// 어드민 신고 대기열의 신고 1건(#399). target 은 대상 스냅샷(현재 상태 기준 미리보기).
public record AdminContentReportResponse(
        Long reportId,
        Long reporterUserId,
        ContentReportTargetType targetType,
        Long targetId,
        Long targetOwnerUserId,
        String targetOwnerNickname,
        ContentReportReason reason,
        String detail,
        ContentReportStatus status,
        Instant createdAt,
        Instant resolvedAt,
        TargetPreview target) {

    public static AdminContentReportResponse of(ContentReport report, String ownerNickname, TargetPreview target) {
        return new AdminContentReportResponse(report.getId(), report.getReporterUserId(), report.getTargetType(),
                report.getTargetId(), report.getTargetOwnerUserId(), ownerNickname, report.getReason(),
                report.getDetail(), report.getStatus(), report.getCreatedAt(), report.getResolvedAt(), target);
    }

    // text: 게시물·댓글 본문 또는 가구 이름. postId: 댓글의 부모 글(게시물이면 자기 id).
    // feedImageIds: 게시물 사진(/admin/reports/feed-images/{imageId}로 조회). assetKey: 가구 공개 CDN key.
    // removed: 이미 삭제·숨김·거래 정지돼 더 볼 수 없는 상태.
    public record TargetPreview(String text, Long postId, List<Long> feedImageIds, String assetKey,
                                boolean removed) {

        public static TargetPreview missing() {
            return new TargetPreview(null, null, List.of(), null, true);
        }
    }
}
