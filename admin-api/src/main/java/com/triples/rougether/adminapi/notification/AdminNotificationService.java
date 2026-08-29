package com.triples.rougether.adminapi.notification;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.policy.NotificationPushPolicy;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.notification.repository.NotificationSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 어드민발 알림 저장 + 커밋 후 FCM push (#348). user-api NotificationService 의 미러 -
// 별도 부팅 앱이라 클래스 재사용이 불가해 같은 계약(내역 저장은 호출 트랜잭션에서 항상, push 는
// 커밋 후 best-effort)을 유지한다. 게이트 판정 정본은 도메인 NotificationPushPolicy 로 공유.
// 수신자는 실사용자 전제(버그 제보 작성자) - 봇은 제보를 만들지 않으므로 봇 가드는 두지 않는다.
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AdminNotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final NotificationSettingRepository notificationSettingRepository;
    private final AdminFcmPushExecutor fcmPushExecutor;
    private final AdminPushResultService pushResultService;
    private final ApplicationEventPublisher eventPublisher;

    public void send(Long userId, NotificationType type, String title, String body, Long refId) {
        User user = userRepository.getReferenceById(userId);
        Notification notification = notificationRepository.save(
                Notification.create(user, type, title, body, refId));

        eventPublisher.publishEvent(new AdminNotificationCreatedEvent(
                notification.getId(), userId, type, title, body));
    }

    // 커밋 이후에만 실행. 예외를 되던지면 이미 끝난 커밋 처리 경로를 타고 원래 요청까지 실패로
    // 응답되는 모순이 생기므로(user-api 동일) push 실패는 여기서 삼킨다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void onNotificationCreated(AdminNotificationCreatedEvent event) {
        try {
            NotificationPushPolicy policy = NotificationPushPolicy.of(
                    notificationSettingRepository.findAllByUserId(event.userId()));
            if (!policy.isPushAllowed(event.userId(), event.type())) {
                pushResultService.markBlocked(event.notificationId());
                return;
            }
            fcmPushExecutor.push(event.notificationId(), event.userId(), event.title(), event.body());
        } catch (Exception e) {
            log.warn("어드민 알림 push 제출 실패 - userId={}", event.userId(), e);
        }
    }

    public record AdminNotificationCreatedEvent(Long notificationId, Long userId, NotificationType type,
                                                String title, String body) {
    }
}
