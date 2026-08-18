package com.triples.rougether.batch.cobweb;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

// MySQL에서 신규 발생·청소 후 재발생·복귀 후 재발생 SQL을 직접 검증한다.
@SpringBootTest(classes = RoomCobwebTriggerIntegrationTest.TestConfig.class)
class RoomCobwebTriggerIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(RoomCobwebTrigger.class)
    static class TestConfig {
    }

    @Autowired private RoomCobwebTrigger trigger;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM room_cobwebs WHERE room_user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM personal_rooms WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        userIds.clear();
    }

    @Test
    void 이틀_미접속_방은_발생하고_청소후_마지막접속과_청소가_모두_이틀전이면_재발생한다() {
        Instant now = Instant.now();
        Long firstInactive = insertUserAndRoom(now.minus(Duration.ofDays(3)));
        Long cleanedInactive = insertUserAndRoom(now.minus(Duration.ofDays(4)));
        Long recent = insertUserAndRoom(now.minus(Duration.ofHours(20)));

        insertCobweb(cleanedInactive, now.minus(Duration.ofDays(5)), now.minus(Duration.ofDays(3)));

        trigger.activateDueCobwebs();

        assertThat(activeCobwebCount(firstInactive)).isEqualTo(1);
        assertThat(activeCobwebCount(cleanedInactive)).isEqualTo(1);
        assertThat(activeCobwebCount(recent)).isZero();
        assertThat(appearedAt(cleanedInactive)).isAfter(now.minusSeconds(5));
    }

    @Test
    void 동거_봇의_방에는_미접속이어도_거미줄이_생기거나_재발생하지_않는다() {
        Instant now = Instant.now();
        Long botNever = insertUserAndRoom(now.minus(Duration.ofDays(30)), true);
        Long botCleaned = insertUserAndRoom(now.minus(Duration.ofDays(30)), true);
        Long human = insertUserAndRoom(now.minus(Duration.ofDays(3)), false);
        insertCobweb(botCleaned, now.minus(Duration.ofDays(10)), now.minus(Duration.ofDays(9)));

        trigger.activateDueCobwebs();

        assertThat(activeCobwebCount(botNever)).isZero();
        assertThat(activeCobwebCount(botCleaned)).isZero();
        assertThat(activeCobwebCount(human)).isEqualTo(1);
    }

    private Long insertUserAndRoom(Instant lastAccessedAt) {
        return insertUserAndRoom(lastAccessedAt, false);
    }

    private Long insertUserAndRoom(Instant lastAccessedAt, boolean bot) {
        Instant createdAt = lastAccessedAt.minus(Duration.ofDays(1));
        jdbcTemplate.update("""
                INSERT INTO users (nickname, last_accessed_at, created_at, updated_at, is_bot, bot_key)
                VALUES ('거미줄 테스트', ?, ?, ?, ?, ?)
                """, timestamp(lastAccessedAt), timestamp(createdAt), timestamp(createdAt),
                bot, bot ? "cobweb-test-bot-" + System.nanoTime() : null);
        Long userId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM users", Long.class);
        userIds.add(userId);
        jdbcTemplate.update(
                "INSERT INTO personal_rooms (user_id, growth_level, updated_at) VALUES (?, 0, ?)",
                userId, timestamp(createdAt));
        return userId;
    }

    private void insertCobweb(Long userId, Instant appearedAt, Instant cleanedAt) {
        jdbcTemplate.update("""
                INSERT INTO room_cobwebs
                    (room_user_id, appeared_at, cleaned_at, cleaned_by_user_id, updated_at)
                VALUES (?, ?, ?, NULL, ?)
                """, userId, timestamp(appearedAt), cleanedAt == null ? null : timestamp(cleanedAt),
                timestamp(cleanedAt == null ? appearedAt : cleanedAt));
    }

    private int activeCobwebCount(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM room_cobwebs WHERE room_user_id = ? AND cleaned_at IS NULL",
                Integer.class, userId);
    }

    private Instant appearedAt(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT appeared_at FROM room_cobwebs WHERE room_user_id = ?",
                Timestamp.class, userId).toInstant();
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
