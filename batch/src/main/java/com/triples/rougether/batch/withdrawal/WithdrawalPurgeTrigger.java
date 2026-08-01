package com.triples.rougether.batch.withdrawal;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 탈퇴 유저 데이터 파기 배치 — 별도 보존 유예 없이 탈퇴 1시간 뒤(access token 창 소진) 첫 실행에서 바로 파기함.
// 유저 단위 트랜잭션(WithdrawalPurgeService)이라 한 명 실패해도 나머지는 진행되고, 실패분은 다음 실행이 회수함.
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawalPurgeTrigger {

    // 한 실행이 붙잡는 시간 상한용. 초과분은 다음 실행이 이어받음(대상 스캔이 자가 복구형).
    private static final int MAX_USERS_PER_RUN = 100;

    // 탈퇴 후에도 access token TTL(30분) 동안 살아있는 요청이 row 를 더 만들 수 있음.
    // purged_at 이 1회성 표식이라 그 창이 닫힌 뒤(1시간 경과)에만 파기해 잔여 데이터 영구 누락을 막음.
    private static final Duration ACCESS_TOKEN_DRAIN = Duration.ofHours(1);

    private final JdbcTemplate jdbcTemplate;
    private final WithdrawalPurgeService purgeService;

    // 정각 잡(리마인더 5분 주기·데이엔드/미션 만료 정각)과 겹치지 않게 매시 30분
    @Scheduled(cron = "0 30 * * * *", zone = "Asia/Seoul")
    public void purgeWithdrawnUsers() {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(ACCESS_TOKEN_DRAIN));
        List<Long> targetIds = jdbcTemplate.queryForList("""
                SELECT id FROM users
                WHERE deleted_at IS NOT NULL AND deleted_at < ? AND purged_at IS NULL
                ORDER BY id LIMIT ?
                """, Long.class, cutoff, MAX_USERS_PER_RUN);
        if (targetIds.isEmpty()) {
            return;
        }
        int purged = 0;
        for (Long userId : targetIds) {
            try {
                purgeService.purgeUser(userId, Instant.now());
                purged++;
            } catch (Exception e) {
                log.error("탈퇴 purge 실패 - userId={} (다음 실행에서 재시도)", userId, e);
            }
        }
        log.info("탈퇴 purge 완료 - 대상={}, 성공={}", targetIds.size(), purged);
    }

    // 트리거 사이에 서버가 죽어 있던 경우 보완 - 기동 시 1회 즉시 수행
    @EventListener(ApplicationReadyEvent.class)
    public void purgeOnStartup() {
        purgeWithdrawnUsers();
    }
}
