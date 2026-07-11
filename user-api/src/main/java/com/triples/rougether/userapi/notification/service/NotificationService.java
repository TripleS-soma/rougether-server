package com.triples.rougether.userapi.notification.service;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final FcmPushExecutor fcmPushExecutor;
    private final ApplicationEventPublisher eventPublisher;

    public void send(Long userId, NotificationType type, String title, String body) {
        User user = userRepository.getReferenceById(userId);
        notificationRepository.save(Notification.create(user, type, title, body, null));

        eventPublisher.publishEvent(new NotificationCreatedEvent(userId, title, body));
    }

    // 트랜잭션 커밋 이후에 알림 수신
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        fcmPushExecutor.push(event.userId(), event.title(), event.body());
    }

    public record NotificationCreatedEvent(Long userId, String title, String body) {
    }
}
