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

    public static NotificationContent feedReply() {
        // 기존 FEED_COMMENT 타입과 게시물 이동 경로를 재사용해 구버전 앱도 알림을 열 수 있음.
        return new NotificationContent(NotificationType.FEED_COMMENT,
                "답글이 달렸어요", "내 댓글에 답글이 달렸어요. 확인해 보세요!",
                "New reply", "Someone replied to your comment. Take a look!");
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

    // 거래소(#432). 가격은 체결가(판매·구매 체결) 또는 구매 대기 가격. 가구명은 영어 번역이 없으면 원문.
    public static NotificationContent marketSellFilled(String itemName, String englishItemName, int price) {
        return new NotificationContent(NotificationType.MARKET_SELL_FILLED,
                "가구가 팔렸어요", "'" + itemName + "'이(가) " + price + "코인에 팔렸어요.",
                "Your furniture sold", "'" + englishItemName + "' sold for " + price + " coins.");
    }

    public static NotificationContent marketBuyFilled(String itemName, String englishItemName, int price) {
        return new NotificationContent(NotificationType.MARKET_BUY_FILLED,
                "구매가 체결됐어요", "'" + itemName + "'을(를) " + price + "코인에 샀어요. 인벤토리를 확인해 보세요.",
                "Your purchase went through", "You bought '" + englishItemName + "' for " + price + " coins. Check your inventory.");
    }

    public static NotificationContent marketBidPlaced(String itemName, String englishItemName, int price) {
        return new NotificationContent(NotificationType.MARKET_BID_PLACED,
                "구매 대기가 들어왔어요", "누군가 '" + itemName + "'을(를) " + price + "코인에 사고 싶어 해요.",
                "Someone wants to buy", "Someone wants to buy '" + englishItemName + "' for " + price + " coins.");
    }

    static final int CHAT_PREVIEW_LENGTH = 100;

    // 집 채팅 새 메시지(#435). 알림함에 저장하지 않는 푸시 전용. 제목 = 집 이름, 본문 = 닉네임 + 앞 100자.
    public static NotificationContent chatMessage(String houseName, String senderNickname, String content) {
        // 글자(코드포인트) 단위로 자름 — UTF-16 단위로 자르면 경계의 이모지가 반쪽으로 남음
        String preview = content.codePointCount(0, content.length()) > CHAT_PREVIEW_LENGTH
                ? content.substring(0, content.offsetByCodePoints(0, CHAT_PREVIEW_LENGTH)) + "…" : content;
        return new NotificationContent(NotificationType.CHAT_MESSAGE,
                houseName, displayName(senderNickname) + ": " + preview,
                houseName, englishName(senderNickname) + ": " + preview);
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
