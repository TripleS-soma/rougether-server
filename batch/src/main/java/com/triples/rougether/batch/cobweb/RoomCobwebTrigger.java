package com.triples.rougether.batch.cobweb;

import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.entity.PushStatus;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RoomCobwebTrigger {
    private static final int PAGE_SIZE = 100;
    private final JdbcTemplate jdbcTemplate;
    private final RoomCobwebActivationService activationService;
    private final NotificationRepository notificationRepository;

    @Scheduled(cron = "0 30 12 * * *", zone = "Asia/Seoul")
    public void activateDueCobwebs() {
        Instant now = Instant.now();
        long cursor = 0;
        while (true) {
            List<Long> userIds = jdbcTemplate.queryForList("""
                    SELECT r.user_id FROM personal_rooms r JOIN users u ON u.id = r.user_id
                    LEFT JOIN room_cobwebs c ON c.room_user_id = r.user_id
                    WHERE r.user_id > ? AND u.deleted_at IS NULL AND u.is_bot = FALSE
                      AND COALESCE(u.last_accessed_at, u.created_at) <= ?
                      AND (c.room_user_id IS NULL OR c.cleaned_at <= ?)
                    ORDER BY r.user_id LIMIT ?
                    """, Long.class, cursor, Timestamp.from(now.minus(Duration.ofDays(2))),
                    Timestamp.from(now.minus(Duration.ofDays(2))), PAGE_SIZE);
            if (userIds.isEmpty()) {
                break;
            }
            for (Long userId : userIds) {
                try {
                    activationService.activate(userId, now);
                } catch (RuntimeException e) {
                    log.warn("거미줄 발생 및 알림 적재 실패 - userId={}", userId, e);
                }
            }
            cursor = userIds.getLast();
        }
        sendPending();
    }

    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Seoul")
    public void sendPending() {
        long cursor = 0;
        while (true) {
            List<Notification> notifications = notificationRepository
                    .findByTypeInAndPushStatusAndIdGreaterThanOrderByIdAsc(
                            List.of(NotificationType.ROOM_COBWEB_APPEARED), PushStatus.PENDING,
                            cursor, PageRequest.of(0, PAGE_SIZE));
            if (notifications.isEmpty()) {
                return;
            }
            for (Notification notification : notifications) {
                try {
                    activationService.sendPending(notification.getUser().getId(), notification.getId());
                } catch (RuntimeException e) {
                    log.warn("거미줄 발생 알림 발송 실패 - notificationId={}", notification.getId(), e);
                }
            }
            cursor = notifications.getLast().getId();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void activateOnStartup() {
        activateDueCobwebs();
    }
}
