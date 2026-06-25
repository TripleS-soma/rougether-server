package com.triples.rougether.domain.routine.entity;

// 잠정 값 — spec 확정 시 조정 필요(동의 거부 등 추가 가능).
public enum AiReviewStatus {
    PENDING,   // 분석 대기
    APPROVED,  // 인증 통과
    REJECTED   // 인증 반려
}
