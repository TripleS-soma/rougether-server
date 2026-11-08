package com.triples.rougether.domain.notification.entity;

public enum PushStatus {
    // 운영자가 알림함만 선택한 경우. 설정 차단이나 전송 성공과 구분함.
    NOT_REQUESTED,
    PENDING,
    SENT,
    // 사용자가 알림을 끈 경우
    BLOCKED,
    FAILED
}
