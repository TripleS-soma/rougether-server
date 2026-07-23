package com.triples.rougether.domain.notification.entity;

public enum PushStatus {
    PENDING,
    SENT,
    // 사용자가 알림을 끈 경우
    BLOCKED,
    FAILED
}
