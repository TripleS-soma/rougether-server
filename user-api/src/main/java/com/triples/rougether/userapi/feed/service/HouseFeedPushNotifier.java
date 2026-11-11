package com.triples.rougether.userapi.feed.service;

import com.triples.rougether.domain.feed.entity.FeedBoardType;
import com.triples.rougether.domain.feed.entity.FeedPost;
import com.triples.rougether.domain.feed.entity.HouseFeedNotificationSetting;
import com.triples.rougether.domain.feed.repository.FeedPostRepository;
import com.triples.rougether.domain.feed.repository.HouseFeedNotificationSettingRepository;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.moderation.repository.UserBlockRepository;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import com.triples.rougether.userapi.notification.message.NotificationContent;
import com.triples.rougether.userapi.notification.message.NotificationMessages;
import com.triples.rougether.userapi.notification.service.NotificationSettingService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

// 집 피드 새 글 푸시(#530). 글 등록이 커밋된 뒤 그 집 구성원에게 FCM 푸시를 보냄.
// 알림함(notification)에는 남기지 않음. 실패는 글 등록 응답에 영향을 주지 않음.
@Component
public class HouseFeedPushNotifier {

    private static final Logger log = LoggerFactory.getLogger(HouseFeedPushNotifier.class);

    private final FeedPostRepository posts;
    private final HouseRepository houses;
    private final HouseMemberRepository members;
    private final HouseFeedNotificationSettingRepository houseSettings;
    private final UserBlockRepository blocks;
    private final NotificationSettingService settings;
    private final FcmPushExecutor fcm;
    private final HouseFeedPushGate gate;
    private final TransactionTemplate readOnly;

    public HouseFeedPushNotifier(FeedPostRepository posts, HouseRepository houses, HouseMemberRepository members,
                                 HouseFeedNotificationSettingRepository houseSettings, UserBlockRepository blocks,
                                 NotificationSettingService settings, FcmPushExecutor fcm, HouseFeedPushGate gate,
                                 PlatformTransactionManager transactionManager) {
        this.posts = posts;
        this.houses = houses;
        this.members = members;
        this.houseSettings = houseSettings;
        this.blocks = blocks;
        this.settings = settings;
        this.fcm = fcm;
        this.gate = gate;
        // 비동기 스레드에서 새 트랜잭션으로 조회함
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly.setReadOnly(true);
    }

    // 채팅 푸시와 같은 이유로 알림 전용 executor 에서 비동기로 처리하고 예외는 로그만 남김.
    @Async("notificationTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPostCreated(HouseFeedPostCreated event) {
        try {
            // 수신자 계산만 읽기 트랜잭션에서 하고 발송은 밖에서 함 — FCM 호출 동안 DB 커넥션을 쥐지 않고,
            // 무효 토큰 정리 같은 발송 중 쓰기가 읽기 전용 트랜잭션에 묶이지 않음.
            deliver(readOnly.execute(status -> plan(event.postId())));
        } catch (RuntimeException e) {
            log.warn("house feed push failed - postId={}", event.postId(), e);
        }
    }

    public void notifyMembers(Long postId) {
        deliver(plan(postId));
    }

    record Delivery(Long userId, String title, String body, Map<String, String> data) {}

    // 호출 시점의 구성원·설정으로 수신자와 문구를 정함.
    List<Delivery> plan(Long postId) {
        // 배포 직후 유예 기간에는 아무에게도 보내지 않음.
        if (!gate.isOpen()) return List.of();
        FeedPost post = posts.findById(postId).orElse(null);
        // 비동기 대기 중 삭제되거나 작성자가 탈퇴한 글은 푸시로 재노출하지 않음.
        if (post == null || !post.isHousePost() || post.getDeletedAt() != null || post.getAuthor().isDeleted()) return List.of();
        House house = houses.findById(post.getHouseId()).filter(found -> !found.isDeleted()).orElse(null);
        if (house == null) return List.of();
        User author = post.getAuthor();
        FeedBoardType boardType = post.getBoardType();
        NotificationContent content = boardType == FeedBoardType.VERIFICATION
                ? NotificationMessages.houseFeedVerification(house.getName(), author.getNickname(), post.getRoutineTitle())
                : NotificationMessages.houseFeedPost(house.getName(), author.getNickname());
        Map<String, String> data = Map.of("type", NotificationType.HOUSE_FEED_POST.name(),
                "houseId", house.getId().toString(), "postId", post.getId().toString(), "boardType", boardType.name());
        List<User> recipients = members.findByHouseIdAndStatusWithUser(house.getId(), HouseMemberStatus.ACTIVE).stream()
                .map(member -> member.getUser())
                .filter(user -> !user.getId().equals(author.getId()) && !user.isDeleted() && !user.isBot() && !user.isAi())
                .toList();
        if (recipients.isEmpty()) return List.of();
        Map<Long, HouseFeedNotificationSetting> byUser = houseSettings
                .findByHouseIdAndUserIdIn(house.getId(), recipients.stream().map(User::getId).toList()).stream()
                .collect(Collectors.toMap(HouseFeedNotificationSetting::getUserId, setting -> setting));
        List<Delivery> deliveries = new ArrayList<>();
        for (User recipient : recipients) {
            Long userId = recipient.getId();
            HouseFeedNotificationSetting setting = byUser.get(userId);
            boolean wanted = setting == null ? HouseFeedNotificationSetting.allowsByDefault(boardType) : setting.allows(boardType);
            // 작성자를 차단한 구성원은 글 자체가 보이지 않으므로 알리지 않음.
            if (!wanted || blocks.existsByBlockerUserIdAndBlockedUserId(userId, author.getId())
                    || !settings.isPushAllowed(userId, NotificationType.HOUSE_FEED_POST)) continue;
            NotificationContent localized = content.localized(recipient.getLanguage());
            deliveries.add(new Delivery(userId, localized.title(), localized.body(), data));
        }
        return deliveries;
    }

    // 수신자 한 명의 발송 실패가 나머지를 막지 않음.
    private void deliver(List<Delivery> deliveries) {
        if (deliveries == null) return;
        for (Delivery delivery : deliveries) {
            try {
                fcm.pushWithoutRecord(delivery.userId(), delivery.title(), delivery.body(), delivery.data());
            } catch (RuntimeException e) {
                log.warn("house feed push failed - userId={}", delivery.userId(), e);
            }
        }
    }
}
