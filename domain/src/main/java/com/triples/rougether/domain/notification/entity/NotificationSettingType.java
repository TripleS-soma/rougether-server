package com.triples.rougether.domain.notification.entity;

// 알림 설정 그룹 키. notification_setting.type 에 저장됨.
// ALL 은 마스터 스위치라 개별 NotificationType 이 소속되지 않음(그룹 값과 무관하게 전체 push 차단).
// SERVICE 는 설정 UI·API 에 노출하지 않는 서비스 메시지 그룹(#348) - off 행이 생길 수 없어
// 사실상 ALL 만 게이트가 된다(문의 답변은 리마인드를 꺼둔 사용자에게도 가야 함).
public enum NotificationSettingType {
    ALL,
    REMINDER,
    HOUSE,
    SERVICE
}
