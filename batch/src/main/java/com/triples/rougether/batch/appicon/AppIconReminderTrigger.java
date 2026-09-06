package com.triples.rougether.batch.appicon;

import com.triples.rougether.domain.appicon.repository.UserAppActivityRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.entity.PushStatus;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app-icon.reminder", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AppIconReminderTrigger {

    private static final int PAGE_SIZE = 100;

    private final UserAppActivityRepository activityRepository;
    private final NotificationRepository notificationRepository;
    private final AppIconReminderService reminderService;
    private final Clock clock;

    @Scheduled(cron = "0 */30 * * * *", zone = "Asia/Seoul")
    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        Instant now = clock.instant();
        if (!AppIconReminderService.isDeliveryTime(now)) {
            return;
        }
        stageCandidates(now);
        sendPending();
    }

    private void stageCandidates(Instant now) {
        long cursor = 0;
        while (true) {
            List<Long> userIds = activityRepository.findReminderCandidates(cursor,
                    now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(4)), now.minus(Duration.ofDays(7)),
                    PageRequest.of(0, PAGE_SIZE));
            if (userIds.isEmpty()) {
                return;
            }
            for (Long userId : userIds) {
                try {
                    reminderService.stage(userId);
                } catch (RuntimeException e) {
                    log.warn("고양이 복귀 알림 적재 실패 - userId={}", userId, e);
                }
            }
            cursor = userIds.getLast();
        }
    }

    private void sendPending() {
        long cursor = 0;
        while (true) {
            List<Notification> notifications =
                    notificationRepository.findByTypeInAndPushStatusAndIdGreaterThanOrderByIdAsc(
                            List.of(NotificationType.APP_INACTIVITY_REMINDER),
                            PushStatus.PENDING, cursor, PageRequest.of(0, PAGE_SIZE));
            if (notifications.isEmpty()) {
                return;
            }
            for (Notification notification : notifications) {
                try {
                    reminderService.sendPending(notification.getUser().getId(), notification.getId());
                } catch (RuntimeException e) {
                    log.warn("고양이 복귀 알림 발송 실패 - notificationId={}", notification.getId(), e);
                }
            }
            cursor = notifications.getLast().getId();
        }
    }
}
