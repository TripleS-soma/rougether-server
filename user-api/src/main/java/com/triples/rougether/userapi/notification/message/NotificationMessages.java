package com.triples.rougether.userapi.notification.message;

import com.triples.rougether.domain.notification.entity.NotificationType;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NotificationMessages {

    public static NotificationContent houseMissionAchieved(String missionTitle) {
        return new NotificationContent(
                NotificationType.HOUSE_MISSION_ACHIEVED,
                "단체 미션 달성!",
                "'" + missionTitle + "' 미션이 목표를 달성했어요. 보상을 받아보세요!");
    }

    public static NotificationContent houseMemberJoined(String nickname) {
        return new NotificationContent(
                NotificationType.HOUSE_MEMBER_JOINED,
                "새 멤버 입주",
                nickname + "님이 집에 입주했어요.");
    }

    public static NotificationContent houseMemberLeft(String nickname) {
        return new NotificationContent(
                NotificationType.HOUSE_MEMBER_LEFT,
                "멤버 퇴거",
                nickname + "님이 집을 떠났어요.");
    }

    public static NotificationContent friendCheer(String senderNickname, String cheerMessage) {
        return new NotificationContent(
                NotificationType.FRIEND_CHEER,
                "응원이 도착했어요",
                senderNickname + "님: " + cheerMessage);
    }
}
