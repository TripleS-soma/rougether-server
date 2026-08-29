package com.triples.rougether.userapi.notification.service;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import com.triples.rougether.userapi.notification.message.NotificationContent;
import java.time.Instant;
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
        // 동거 봇(#308)은 알림을 읽을 주체가 없다 — 저장·푸시 모두 건너뛴다(응원·입주·미션 달성 알림 등 호출처 공통).
        if (userRepository.existsByIdAndBotTrue(userId)) {
            return;
        }
        User user = userRepository.getReferenceById(userId);
        Notification notification = notificationRepository.save(
                Notification.create(user, content.type(), content.title(), content.body(), refId));

        eventPublisher.publishEvent(new NotificationCreatedEvent(
                notification.getId(), userId, content.type(), content.title(), content.body()));
    }

    // 같은 수신자·타입·본문 알림이 since 이후 이미 갔으면 저장·push 모두 건너뛴다.
    // 신청 철회→재신청 반복처럼 호출자가 임의로 되풀이할 수 있는 사용자 대상 알림의 증폭 방어선.
    // 억제창(since) 정책은 호출처가 정하며, 동시 요청 사이에서는 best-effort 다(락 없음).
    public void sendUnlessDuplicatedSince(Long userId, NotificationContent content, Long refId, Instant since) {
        if (notificationRepository.existsByUserAndTypeAndBodySince(
                userId, content.type(), content.body(), since)) {
            return;
        }
        send(userId, content, refId);
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
