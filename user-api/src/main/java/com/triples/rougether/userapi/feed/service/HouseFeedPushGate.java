package com.triples.rougether.userapi.feed.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 집 피드 새 글 푸시의 유예 기간(#530). 집 피드 화면이 없는 구버전 앱에 열 수 없는 푸시가 가지 않도록,
// 기능이 배포된 뒤(설정 테이블 migration 적용 시각) grace 만큼은 푸시를 보내지 않음. 글 등록과 설정 API 는 유예와 무관함.
@Component
public class HouseFeedPushGate {

    private static final Logger log = LoggerFactory.getLogger(HouseFeedPushGate.class);
    // 번호가 바뀌어도 찾도록 설명으로 조회함(V106__add_house_feed_notification_settings).
    static final String MIGRATION_DESCRIPTION = "add house feed notification settings";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration grace;
    // 적용 시각은 바뀌지 않으므로 한 번 읽으면 다시 묻지 않음.
    private volatile Instant deployedAt;

    public HouseFeedPushGate(JdbcTemplate jdbc, Clock clock,
                             @Value("${feed.house-post-push.grace:24h}") Duration grace) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.grace = grace;
    }

    public boolean isOpen() {
        if (grace.isZero() || grace.isNegative()) return true;
        Instant since = deployedAt();
        // 적용 시각을 알 수 없으면 보내지 않음 — 푸시는 못 받아도 글은 집 피드에서 볼 수 있음.
        return since != null && !clock.instant().isBefore(since.plus(grace));
    }

    private Instant deployedAt() {
        Instant known = deployedAt;
        if (known != null) return known;
        try {
            List<Timestamp> found = jdbc.queryForList("SELECT installed_on FROM flyway_schema_history "
                    + "WHERE description = ? AND success = TRUE ORDER BY installed_rank LIMIT 1", Timestamp.class, MIGRATION_DESCRIPTION);
            if (found.isEmpty() || found.getFirst() == null) {
                log.warn("house feed push gate - migration '{}' not found in flyway history, push stays off", MIGRATION_DESCRIPTION);
                return null;
            }
            return deployedAt = found.getFirst().toInstant();
        } catch (DataAccessException e) {
            log.warn("house feed push gate - cannot read flyway history, push stays off", e);
            return null;
        }
    }
}
