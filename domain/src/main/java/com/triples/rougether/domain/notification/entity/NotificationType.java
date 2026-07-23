package com.triples.rougether.domain.notification.entity;

public enum NotificationType {
    HOUSE_KICK(NotificationSettingType.HOUSE),
    ROUTINE_REMINDER(NotificationSettingType.REMINDER),
    TODO_REMINDER(NotificationSettingType.REMINDER),
    FRIEND_CHEER(NotificationSettingType.HOUSE),
    HOUSE_MISSION_ACHIEVED(NotificationSettingType.HOUSE),
    HOUSE_MEMBER_JOINED(NotificationSettingType.HOUSE),
    HOUSE_MEMBER_LEFT(NotificationSettingType.HOUSE);

    // 소속 알림 설정 그룹. 생성자 인자라 새 타입 추가 시 그룹 지정이 컴파일 타임에 강제됨.
    private final NotificationSettingType settingType;

    NotificationType(NotificationSettingType settingType) {
        this.settingType = settingType;
    }

    public NotificationSettingType settingType() {
        return settingType;
    }
}
