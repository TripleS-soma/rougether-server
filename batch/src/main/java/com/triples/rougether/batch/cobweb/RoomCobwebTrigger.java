package com.triples.rougether.batch.cobweb;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 마지막 접속 또는 마지막 청소 이후 유예기간이 지난 방에 거미줄을 활성화한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomCobwebTrigger {

    // MVP 정책값. 추후 운영 설정으로 분리 가능함.
    private static final Duration INACTIVITY_GRACE = Duration.ofDays(2);

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    @Scheduled(cron = "0 30 12 * * *", zone = "Asia/Seoul")
    public void activateDueCobwebs() {
        Instant now = Instant.now();
        Timestamp cutoff = Timestamp.from(now.minus(INACTIVITY_GRACE));
        int inserted = jdbcTemplate.update("""
                INSERT INTO room_cobwebs (room_user_id, appeared_at, cleaned_at, cleaned_by_user_id, updated_at)
                SELECT r.user_id, ?, NULL, NULL, ?
                FROM personal_rooms r
                JOIN users u ON u.id = r.user_id
                WHERE u.deleted_at IS NULL
                  AND COALESCE(u.last_accessed_at, u.created_at) <= ?
                  AND NOT EXISTS (SELECT 1 FROM room_cobwebs c WHERE c.room_user_id = r.user_id)
                """, Timestamp.from(now), Timestamp.from(now), cutoff);

        int reactivated = jdbcTemplate.update("""
                UPDATE room_cobwebs c
                JOIN users u ON u.id = c.room_user_id
                SET c.appeared_at = ?, c.cleaned_at = NULL, c.cleaned_by_user_id = NULL, c.updated_at = ?
                WHERE u.deleted_at IS NULL
                  AND c.cleaned_at IS NOT NULL
                  AND GREATEST(COALESCE(u.last_accessed_at, u.created_at), c.cleaned_at) <= ?
                """, Timestamp.from(now), Timestamp.from(now), cutoff);

        if (inserted + reactivated > 0) {
            log.info("방 거미줄 활성화 - inserted={}, reactivated={}", inserted, reactivated);
        }
    }

    // 12:30에 서버가 내려가 있었던 날도 다음 기동 시 누락 없이 보정한다. 쿼리는 방별 멱등이다.
    @Transactional
    @EventListener(ApplicationReadyEvent.class)
    public void activateOnStartup() {
        activateDueCobwebs();
    }
}
