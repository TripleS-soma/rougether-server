package com.triples.rougether.domain.notification.entity;

public enum NotificationType {
    HOUSE_KICK(NotificationSettingType.HOUSE),
    ROUTINE_REMINDER(NotificationSettingType.REMINDER),
    TODO_REMINDER(NotificationSettingType.REMINDER),
    DAILY_INCOMPLETE_DIGEST(NotificationSettingType.REMINDER),
    // AI 주간 회고 도착. 별도 설정 그룹을 만들지 않고 REMINDER 에 편입함(결정값) - 설정 UI·기본값·마이그레이션 스코프 회피
    WEEKLY_REPORT(NotificationSettingType.REMINDER),
    FRIEND_CHEER(NotificationSettingType.HOUSE),
    HOUSE_MISSION_ACHIEVED(NotificationSettingType.HOUSE),
    HOUSE_MEMBER_JOINED(NotificationSettingType.HOUSE),
    HOUSE_MEMBER_LEFT(NotificationSettingType.HOUSE),
    HOUSE_JOIN_REQUEST_CREATED(NotificationSettingType.HOUSE),
    HOUSE_JOIN_REQUEST_REJECTED(NotificationSettingType.HOUSE),
    HOUSE_JOIN_REQUEST_ACCEPTED(NotificationSettingType.HOUSE),
    ROOM_COBWEB_CLEANED(NotificationSettingType.HOUSE),
    // 버그 제보 답장 도착(#348). refId=제보 id. admin-api 가 발송하는 첫 타입.
    BUG_REPORT_REPLY(NotificationSettingType.SERVICE);

    // 소속 알림 설정 그룹. 생성자 인자라 새 타입 추가 시 그룹 지정이 컴파일 타임에 강제됨.
    private final NotificationSettingType settingType;

    NotificationType(NotificationSettingType settingType) {
        this.settingType = settingType;
    }

    public NotificationSettingType settingType() {
        return settingType;
    }
}
