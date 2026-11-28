package com.triples.rougether.domain.moderation.entity;

// 신고 출처(#478). USER 는 사용자가 직접 한 신고(신고자 필수), AUTO 는 자동 유해 판정이 만든 시스템 신고(신고자 없음).
public enum ContentReportSource {
    USER, AUTO
}
