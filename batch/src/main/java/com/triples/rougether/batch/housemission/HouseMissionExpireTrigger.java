package com.triples.rougether.batch.housemission;

import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 단체 미션 만료 전이 (#205) - ends_at 이 지난 ACTIVE 미션을 EXPIRED 로 내린다.
// 조건(ends_at < now)이 자가 복구형이라 catch-up 계획 없이 멱등 UPDATE 1문으로 충분함.
// routine day-end 잡(#154/#158)과 도메인이 달라 별도 컴포넌트로 분리하고, 실행 주기만 같은 매시 정각을 쓴다.
@Slf4j
@Component
@RequiredArgsConstructor
public class HouseMissionExpireTrigger {

    private final JdbcTemplate jdbcTemplate;

    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Seoul")
    public void expireOverdueMissions() {
        // soft delete 행은 조회에 안 나와 전이해도 무해하지만, 스캔 축소·의미 명확화를 위해 제외한다.
        int expired = jdbcTemplate.update("""
                UPDATE house_missions SET status = 'EXPIRED'
                WHERE status = 'ACTIVE' AND deleted_at IS NULL
                  AND ends_at IS NOT NULL AND ends_at < ?
                """, Timestamp.from(Instant.now()));
        if (expired > 0) {
            log.info("단체 미션 만료 전이 - count={}", expired);
        }
    }

    // 트리거 사이에 서버가 죽어 있던 경우 보완 - 기동 시 1회 즉시 수행
    @EventListener(ApplicationReadyEvent.class)
    public void expireOnStartup() {
        expireOverdueMissions();
    }
}
