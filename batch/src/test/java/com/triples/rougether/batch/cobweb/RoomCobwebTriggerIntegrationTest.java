package com.triples.rougether.batch.cobweb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.triples.rougether.batch.reminder.ReminderPushWriter;
import com.triples.rougether.infra.fcm.FcmSender;
import com.triples.rougether.infra.fcm.FcmSendResult;
import org.springframework.transaction.support.TransactionTemplate;

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
    @org.springframework.boot.persistence.autoconfigure.EntityScan("com.triples.rougether.domain")
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories("com.triples.rougether.domain")
    @org.springframework.data.jpa.repository.config.EnableJpaAuditing
    @Import({RoomCobwebTrigger.class, RoomCobwebActivationService.class, ReminderPushWriter.class})
    static class TestConfig {
    }

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private FcmSender fcm;
    @Autowired private TransactionTemplate tx;
    @Autowired private RoomCobwebActivationService activationService;
    @Autowired private RoomCobwebTrigger trigger;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM user_device_token WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM notification_setting WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM notification WHERE user_id = ?", userId);
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
        assertThat(notificationCount(firstInactive)).isEqualTo(1);
        assertThat(notificationCount(cleanedInactive)).isEqualTo(1);
        trigger.activateDueCobwebs();
        assertThat(notificationCount(firstInactive)).isEqualTo(1);
        assertThat(notificationCount(cleanedInactive)).isEqualTo(1);
        // 다음 회차는 새 알림을 저장한다.
        jdbcTemplate.update("UPDATE room_cobwebs SET cleaned_at = ? WHERE room_user_id = ?",
                timestamp(now.minus(Duration.ofDays(3))), firstInactive);
        activationService.activate(firstInactive, Instant.now());
        assertThat(notificationCount(firstInactive)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND push_status = 'PENDING'",
                Integer.class, firstInactive)).isEqualTo(1);
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

    @Test
    void 발생_알림은_커밋후_한번만_FCM으로_보내고_복귀해도_거미줄은_남는다() {
        Long userId = insertUserAndRoom(Instant.now().minus(Duration.ofDays(3)));
        jdbcTemplate.update("""
                INSERT INTO user_device_token (user_id, token, platform, created_at, updated_at)
                VALUES (?, ?, 'IOS', NOW(), NOW())
                """, userId, "cobweb-test-" + userId);
        when(fcm.send(anyList(), anyString(), anyString(), anyMap()))
                .thenReturn(new FcmSendResult(1, List.of()));
        activationService.activate(userId, Instant.now());
        verifyNoInteractions(fcm);
        jdbcTemplate.update("UPDATE users SET last_accessed_at = NOW() WHERE id = ?", userId);
        trigger.sendPending();
        trigger.sendPending();
        assertThat(activeCobwebCount(userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT push_status FROM notification WHERE user_id = ?", String.class, userId))
                .as("%s", jdbcTemplate.queryForMap("SELECT c.appeared_at, n.created_at, n.push_status FROM room_cobwebs c JOIN notification n ON n.user_id = c.room_user_id WHERE c.room_user_id = ?", userId))
                .isEqualTo("SENT");
        verify(fcm, times(1)).send(eq(List.of("cobweb-test-" + userId)),
                eq("방에 거미줄이 생겼다냥!"), anyString(),
                argThat(data -> "ROOM_COBWEB_APPEARED".equals(data.get("type"))
                        && "myRoom".equals(data.get("screen"))));
    }

    @Test
    void 생성_트랜잭션이_롤백되면_거미줄과_알림이_함께_사라진다() {
        Long userId = insertUserAndRoom(Instant.now().minus(Duration.ofDays(3)));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            activationService.activate(userId, Instant.now());
            throw new IllegalStateException("롤백 검증");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(activeCobwebCount(userId)).isZero();
        assertThat(notificationCount(userId)).isZero();
        verifyNoInteractions(fcm);
    }

    @Test
    void 발송전에_청소됐다면_발생_알림은_보내지_않는다() {
        Long userId = insertUserAndRoom(Instant.now().minus(Duration.ofDays(3)));
        activationService.activate(userId, Instant.now());
        jdbcTemplate.update("UPDATE room_cobwebs SET cleaned_at = NOW() WHERE room_user_id = ?", userId);
        trigger.sendPending();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT push_status FROM notification WHERE user_id = ?", String.class, userId))
                .isEqualTo("BLOCKED");
        verifyNoInteractions(fcm);
    }

    @Test
    void 리마인드_알림을_끈_사용자에게는_FCM을_보내지_않는다() {
        Long userId = insertUserAndRoom(Instant.now().minus(Duration.ofDays(3)));
        jdbcTemplate.update("""
                INSERT INTO notification_setting (user_id, type, enabled, created_at, updated_at)
                VALUES (?, 'REMINDER', FALSE, NOW(), NOW())
                """, userId);
        activationService.activate(userId, Instant.now());
        trigger.sendPending();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT push_status FROM notification WHERE user_id = ?", String.class, userId))
                .isEqualTo("BLOCKED");
        verifyNoInteractions(fcm);
    }

    @Test
    void 새_회차가_발생하면_이전_회차의_미발송_알림은_만료한다() {
        Long userId = insertUserAndRoom(Instant.now().minus(Duration.ofDays(4)));
        activationService.activate(userId, Instant.now());
        Long oldId = jdbcTemplate.queryForObject(
                "SELECT id FROM notification WHERE user_id = ?", Long.class, userId);
        jdbcTemplate.update("UPDATE room_cobwebs SET cleaned_at = ? WHERE room_user_id = ?",
                timestamp(Instant.now().minus(Duration.ofDays(3))), userId);
        activationService.activate(userId, Instant.now());
        assertThat(notificationCount(userId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT push_status FROM notification WHERE id = ?", String.class, oldId))
                .isEqualTo("BLOCKED");
    }

    @Test
    void 동시에_발생을_처리해도_알림은_한건만_저장한다() throws Exception {
        Long userId = insertUserAndRoom(Instant.now().minus(Duration.ofDays(3)));
        Instant now = Instant.now();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 2; i++) {
                tasks.add(executor.submit(() -> {
                    start.await();
                    activationService.activate(userId, now);
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) {
                task.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        assertThat(activeCobwebCount(userId)).isEqualTo(1);
        assertThat(notificationCount(userId)).isEqualTo(1);
    }

    private int notificationCount(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'ROOM_COBWEB_APPEARED'",
                Integer.class, userId);
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
