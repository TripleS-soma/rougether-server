package com.triples.rougether.userapi.notification.message;

import com.triples.rougether.domain.notification.entity.NotificationType;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import com.triples.rougether.domain.house.entity.CheerType;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NotificationMessages {

    public static NotificationContent feedComment() {
        // 삭제·탈퇴한 댓글 본문이나 닉네임 사본을 알림함에 남기지 않음. 내용은 이동한 게시물에서 조회함.
        return new NotificationContent(NotificationType.FEED_COMMENT,
                "새 댓글이 달렸어요", "내 게시물에 새 댓글이 달렸어요. 확인해 보세요!",
                "New comment", "Someone commented on your post. Take a look!");
    }

    public static NotificationContent houseMissionAchieved(String missionTitle) {
        return new NotificationContent(
                NotificationType.HOUSE_MISSION_ACHIEVED,
                "단체 미션 달성!",
                "'" + missionTitle + "' 미션이 목표를 달성했어요. 보상을 받아보세요!",
                "House mission complete!", "Your house completed '" + missionTitle + "'. Claim your reward!");
    }

    public static NotificationContent houseMemberJoined(String nickname) {
        return new NotificationContent(
                NotificationType.HOUSE_MEMBER_JOINED,
                "새 멤버 입주",
                displayName(nickname) + "님이 집에 입주했어요.",
                "A new housemate", englishName(nickname) + " joined your house.");
    }

    public static NotificationContent houseMemberLeft(String nickname) {
        return new NotificationContent(
                NotificationType.HOUSE_MEMBER_LEFT,
                "멤버 퇴거",
                displayName(nickname) + "님이 집을 떠났어요.",
                "A housemate left", englishName(nickname) + " left your house.");
    }

    public static NotificationContent friendCheer(String senderNickname, String cheerMessage) {
        return new NotificationContent(
                NotificationType.FRIEND_CHEER,
                "응원이 도착했어요",
                displayName(senderNickname) + "님: " + cheerMessage,
                "Someone is cheering you on", englishName(senderNickname) + ": " + cheerMessage);
    }

    public static NotificationContent houseJoinRequestCreated(String applicantNickname, String houseName) {
        return new NotificationContent(
                NotificationType.HOUSE_JOIN_REQUEST_CREATED,
                "입주 신청 도착",
                displayName(applicantNickname) + "님이 '" + houseName + "'에 입주를 신청했어요. 수락하면 입주가 확정돼요.",
                "New join request", englishName(applicantNickname) + " asked to join '" + houseName + "'. Accept to welcome them.");
    }

    public static NotificationContent friendCheer(String senderNickname,
            CheerType cheer) {
        var korean = friendCheer(senderNickname, cheer.message());
        String english = switch (cheer) {
            case GREAT -> "You're doing great!";
            case SUPPORT -> "I'm cheering you on!";
            case BEST -> "You're amazing today!";
        };
        return new NotificationContent(korean.type(), korean.title(), korean.body(),
                korean.englishTitle(), englishName(senderNickname) + ": " + english);
    }

    private static String englishName(String nickname) {
        return nickname == null || nickname.isBlank() ? "A neighbor" : nickname;
    }

    // 닉네임은 온보딩 전(미설정)·익명화 계정에서 null 일 수 있다 - "null님이" 로 찍히지 않게 폴백한다.
    private static String displayName(String nickname) {
        return nickname == null || nickname.isBlank() ? "이웃" : nickname;
    }

    public static NotificationContent houseJoinRequestRejected(String houseName) {
        return new NotificationContent(
                NotificationType.HOUSE_JOIN_REQUEST_REJECTED,
                "입주 신청 거절",
                "'" + houseName + "' 입주 신청이 거절됐어요.",
                "Join request declined", "Your request to join '" + houseName + "' was declined.");
    }

    public static NotificationContent houseJoinRequestAccepted(String houseName) {
        return new NotificationContent(
                NotificationType.HOUSE_JOIN_REQUEST_ACCEPTED,
                "입주 신청 승인",
                "'" + houseName + "' 입주 신청이 승인됐어요.",
                "Join request accepted", "Your request to join '" + houseName + "' was accepted.");
    }

    public static NotificationContent roomCobwebCleaned(String cleanerNickname) {
        return new NotificationContent(
                NotificationType.ROOM_COBWEB_CLEANED,
                "친구가 거미줄을 청소해줬다냥!",
                displayName(cleanerNickname) + "님이 거미줄을 치워줬어요. 깨끗해진 방에 놀러 오세요!",
                "A friend cleaned your room!", englishName(cleanerNickname) + " cleared the cobwebs. Come see your clean room!");
    }
}
