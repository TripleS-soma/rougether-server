package com.triples.rougether.batch.cobweb;

import com.triples.rougether.batch.reminder.ReminderPushWriter;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.entity.PushStatus;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.room.repository.RoomCobwebRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class RoomCobwebActivationService {
    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final RoomCobwebRepository cobwebRepository;
    private final ReminderPushWriter pushWriter;

    // 사용자 잠금으로 동시 배치를 직렬화하고 발생과 알림 적재를 함께 커밋한다.
    public void activate(Long userId, Instant now) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null || user.isDeleted() || user.isBot()) {
            return;
        }
        Timestamp cutoff = Timestamp.from(now.minus(Duration.ofDays(2)));
        int inserted = jdbcTemplate.update("""
                INSERT INTO room_cobwebs (room_user_id, appeared_at, cleaned_at, cleaned_by_user_id, updated_at)
                SELECT r.user_id, ?, NULL, NULL, ?
                FROM personal_rooms r JOIN users u ON u.id = r.user_id
                WHERE u.id = ? AND COALESCE(u.last_accessed_at, u.created_at) <= ?
                  AND NOT EXISTS (SELECT 1 FROM room_cobwebs c WHERE c.room_user_id = r.user_id)
                """, Timestamp.from(now), Timestamp.from(now), userId, cutoff);
        int reactivated = jdbcTemplate.update("""
                UPDATE room_cobwebs c JOIN users u ON u.id = c.room_user_id
                SET c.appeared_at = ?, c.cleaned_at = NULL, c.cleaned_by_user_id = NULL, c.updated_at = ?
                WHERE u.id = ? AND c.cleaned_at IS NOT NULL
                  AND GREATEST(COALESCE(u.last_accessed_at, u.created_at), c.cleaned_at) <= ?
                """, Timestamp.from(now), Timestamp.from(now), userId, cutoff);
        if (inserted + reactivated > 0) {
            // 재발생 이전 회차의 미발송 알림을 함께 만료한다. JDBC/JPA 시각 표현을 비교하지 않는다.
            jdbcTemplate.update("""
                    UPDATE notification SET push_status = 'BLOCKED'
                    WHERE user_id = ? AND type = 'ROOM_COBWEB_APPEARED' AND push_status = 'PENDING'
                    """, userId);
            notificationRepository.save(Notification.create(user, NotificationType.ROOM_COBWEB_APPEARED,
                    "방에 거미줄이 생겼다냥!", "방에 놀러 와서 거미줄을 톡 눌러 치워주세요!", userId));
        }
    }

    // 발생 커밋 뒤 별도 트랜잭션에서 발송한다. 미발송 내역은 다음 실행에서 회수한다.
    public void sendPending(Long userId, Long notificationId) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        Notification notification = notificationRepository.findByIdAndUserId(notificationId, userId).orElse(null);
        if (notification == null || notification.getType() != NotificationType.ROOM_COBWEB_APPEARED
                || notification.getPushStatus() != PushStatus.PENDING) {
            return;
        }
        var cobweb = cobwebRepository.findActiveForUpdate(userId).orElse(null);
        if (user == null || user.isDeleted() || user.isBot() || cobweb == null) {
            notification.markPushBlocked();
            return;
        }
        pushWriter.write(new Chunk<>(notification));
    }
}
