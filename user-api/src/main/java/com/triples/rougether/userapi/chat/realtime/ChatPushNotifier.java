package com.triples.rougether.userapi.chat.realtime;

import com.triples.rougether.domain.chat.entity.ChatMessage;
import com.triples.rougether.domain.chat.entity.ChatRoom;
import com.triples.rougether.domain.chat.repository.ChatMessageRepository;
import com.triples.rougether.domain.chat.repository.ChatReadStateRepository;
import com.triples.rougether.domain.chat.repository.ChatRoomRepository;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.userapi.chat.service.ChatAccessPolicy;
import com.triples.rougether.userapi.chat.service.ChatMessageSent;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import com.triples.rougether.userapi.notification.message.NotificationContent;
import com.triples.rougether.userapi.notification.message.NotificationMessages;
import com.triples.rougether.userapi.notification.service.NotificationSettingService;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

// 집 채팅 푸시(#435). 메시지 저장이 커밋된 뒤 채팅을 보고 있지 않은 구성원에게 메시지마다 FCM 푸시를 보냄.
// 알림함(notification)에는 남기지 않음 — 기록은 채팅 화면이 맡음. 실패는 전송 응답에 영향을 주지 않음.
@Component
public class ChatPushNotifier {

    private static final Logger log = LoggerFactory.getLogger(ChatPushNotifier.class);

    private final ChatMessageRepository messages;
    private final ChatRoomRepository rooms;
    private final ChatReadStateRepository reads;
    private final HouseRepository houses;
    private final UserRepository users;
    private final ChatAccessPolicy access;
    private final ChatSocketHandler sockets;
    private final NotificationSettingService settings;
    private final FcmPushExecutor fcm;
    private final TransactionTemplate readOnly;

    public ChatPushNotifier(ChatMessageRepository messages, ChatRoomRepository rooms, ChatReadStateRepository reads,
                            HouseRepository houses, UserRepository users, ChatAccessPolicy access,
                            ChatSocketHandler sockets, NotificationSettingService settings, FcmPushExecutor fcm,
                            PlatformTransactionManager transactionManager) {
        this.messages = messages;
        this.rooms = rooms;
        this.reads = reads;
        this.houses = houses;
        this.users = users;
        this.access = access;
        this.sockets = sockets;
        this.settings = settings;
        this.fcm = fcm;
        // 비동기 스레드에서 새 트랜잭션으로 조회함
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly.setReadOnly(true);
    }

    // 요청 스레드에서 동기로 돌면 커밋 직후 아직 반납 전인 원래 커넥션을 쥔 채 새 트랜잭션 커넥션을 더 잡아,
    // 동시 전송이 몰리면 커넥션 풀이 바닥남. 알림 전용 executor 에서 비동기로 처리하고 예외는 로그만 남김.
    @Async("notificationTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageSent(ChatMessageSent event) {
        try {
            readOnly.executeWithoutResult(status -> notifyRecipients(event.messageId()));
        } catch (RuntimeException e) {
            log.warn("chat push failed - messageId={}", event.messageId(), e);
        }
    }

    private void notifyRecipients(Long messageId) {
        ChatMessage message = messages.findById(messageId).orElseThrow();
        ChatRoom room = rooms.findById(message.getRoomId()).orElseThrow();
        String houseName = houses.findById(room.getHouseId()).map(House::getName).orElse("");
        User sender = message.getSender();
        NotificationContent content = NotificationMessages.chatMessage(houseName, sender.getNickname(),
                message.getContent());
        Map<String, String> data = Map.of("type", NotificationType.CHAT_MESSAGE.name(),
                "roomId", room.getId().toString(), "houseId", room.getHouseId().toString());
        Set<Long> muted = new HashSet<>(reads.findPushMutedUserIds(room.getId()));
        for (ChatAccessPolicy.Participant participant : access.participants(room)) {
            Long userId = participant.userId();
            if (userId.equals(sender.getId()) || muted.contains(userId) || sockets.isWatching(userId, room.getId())
                    || !settings.isPushAllowed(userId, NotificationType.CHAT_MESSAGE)) {
                continue;
            }
            users.findById(userId).filter(user -> !user.isBot()).ifPresent(user -> {
                NotificationContent localized = content.localized(user.getLanguage());
                fcm.pushWithoutRecord(userId, localized.title(), localized.body(), data);
            });
        }
    }
}
