package com.triples.rougether.domain.moderation.entity;

// 신고 처리 상태(#399). RECEIVED(검토 대기) → ACTIONED(숨김 조치) | DISMISSED(조치 없음 종료).
public enum ContentReportStatus {
    RECEIVED, ACTIONED, DISMISSED
}
