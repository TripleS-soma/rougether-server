package com.triples.rougether.userapi.notification.service;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import com.triples.rougether.userapi.notification.message.NotificationContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final FcmPushExecutor fcmPushExecutor;
    private final NotificationSettingService notificationSettingService;
    private final NotificationPushStatusService notificationPushStatusService;
    private final ApplicationEventPublisher eventPublisher;

    public void send(Long userId, NotificationContent content) {
        send(userId, content, null);
    }

    public void send(Long userId, NotificationContent content, Long refId) {
        User user = userRepository.getReferenceById(userId);
        Notification notification = notificationRepository.save(
                Notification.create(user, content.type(), content.title(), content.body(), refId));

        eventPublisher.publishEvent(new NotificationCreatedEvent(
                notification.getId(), userId, content.type(), content.title(), content.body()));
    }

    // 트랜잭션 커밋 이후에 알림 수신.
    // AFTER_COMMIT 리스너가 예외를 던지면 이미 끝난 커밋 처리 경로를 타고 원래 요청까지 되던져진다
    // (DB 저장은 성공했는데 요청은 실패로 응답되는 모순) — push는 원래도 best-effort라 여기서 삼킨다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        try {
            // 알림 내역(notification)은 이미 저장됐음 — 설정 off는 push만 막는다.
            if (!notificationSettingService.isPushAllowed(event.userId(), event.type())) {
                notificationPushStatusService.markBlocked(event.notificationId());
                return;
            }
            fcmPushExecutor.push(event.notificationId(), event.userId(), event.title(), event.body());
        } catch (Exception e) {
            log.warn("알림 push 제출 실패 - userId={}", event.userId(), e);
        }
    }

    public record NotificationCreatedEvent(Long notificationId, Long userId, NotificationType type,
                                           String title, String body) {
    }
}
