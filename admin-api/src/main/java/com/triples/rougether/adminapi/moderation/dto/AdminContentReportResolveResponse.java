package com.triples.rougether.adminapi.moderation.dto;

import com.triples.rougether.domain.moderation.entity.ContentReportStatus;

// resolvedCount: 같은 대상의 대기 신고를 함께 닫은 건수(요청한 신고 포함).
public record AdminContentReportResolveResponse(Long reportId, ContentReportStatus status, int resolvedCount) {
}
