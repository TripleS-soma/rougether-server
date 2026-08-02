package com.triples.rougether.domain.notification.entity;

// 알림 설정 그룹 키. notification_setting.type 에 저장됨.
// ALL 은 마스터 스위치라 개별 NotificationType 이 소속되지 않음(그룹 값과 무관하게 전체 push 차단).
public enum NotificationSettingType {
    ALL,
    REMINDER,
    HOUSE
}
