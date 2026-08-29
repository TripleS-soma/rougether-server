package com.triples.rougether.adminapi.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.adminapi.notification.dto.AdminNotificationDigestMetricsResponse.DayMetric;
import com.triples.rougether.adminapi.notification.service.AdminNotificationDigestMetricsService;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.digest.entity.DailyIncompleteDigest;
import com.triples.rougether.domain.notification.digest.entity.DailyIncompleteDigestTarget;
import com.triples.rougether.domain.notification.digest.repository.DailyIncompleteDigestRepository;
import com.triples.rougether.domain.notification.digest.repository.DailyIncompleteDigestTargetRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.entity.PushStatus;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AdminNotificationDigestMetricsIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = LocalDateTime.of(2030, 9, 2, 12, 0).atZone(KST).toInstant();
    private static final LocalDate DIGEST_DATE = LocalDate.of(2030, 9, 1);
    private static final Instant CREATED_AT = LocalDateTime.of(2030, 9, 1, 21, 0).atZone(KST).toInstant();
    private static final Instant SENT_AT = CREATED_AT.plusSeconds(60);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedKstClock() {
            return Clock.fixed(NOW, KST);
        }
    }

    @Autowired
    AdminNotificationDigestMetricsService metricsService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    RoutineRepository routineRepository;
    @Autowired
    RoutineLogRepository routineLogRepository;
    @Autowired
    TodoRepository todoRepository;
    @Autowired
    DailyIncompleteDigestRepository digestRepository;
    @Autowired
    DailyIncompleteDigestTargetRepository targetRepository;
    @Autowired
    NotificationRepository notificationRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    EntityManager entityManager;

    @Test
    void ROUTINE과_TODO_스냅숏을_sentAt_후_119분_완료_전환으로_각각_집계한다() {
        User routineUser = userRepository.save(User.signUp("digest-routine@rougether.dev"));
        Routine routine = routineRepository.save(Routine.create(
                routineUser, null, "루틴", AuthType.CHECK, "DAILY", null, null,
                DIGEST_DATE.minusDays(1), null));
        routine.assignOriginToSelf();
        routineRepository.save(routine);
        routine.softDelete(CREATED_AT.minusSeconds(60));
        routineRepository.save(routine);
        routineRepository.save(routine.copyAsNewVersion(
                null, "수정 루틴", AuthType.CHECK, "DAILY", null, null,
                DIGEST_DATE.minusDays(1), null));
        DailyIncompleteDigest routineDigest = saveDigest(routineUser, routine.getOriginRoutineId(), null);

        routineLogRepository.save(RoutineLog.complete(
                routine, DIGEST_DATE, SENT_AT.plusSeconds(119 * 60), CurrencyType.COIN, 10));

        User todoUser = userRepository.save(User.signUp("digest-todo@rougether.dev"));
        Todo todo = todoRepository.save(Todo.create(todoUser, null, "투두", null, DIGEST_DATE, null));
        DailyIncompleteDigest todoDigest = saveDigest(todoUser, null, todo.getId());
        todo.complete(CurrencyType.COIN, 10, SENT_AT.plusSeconds(119 * 60));
        todoRepository.save(todo);
        digestRepository.flush();
        setDispatchState(routineDigest, PushStatus.SENT);
        setDispatchState(todoDigest, PushStatus.SENT);
        entityManager.clear();

        DayMetric metric = metricsService.getMetrics(2).days().stream()
                .filter(day -> day.date().equals(DIGEST_DATE))
                .findFirst()
                .orElseThrow();

        assertThat(metric.createdCount()).isEqualTo(2);
        assertThat(metric.sentCount()).isEqualTo(2);
        assertThat(metric.conversionMeasuredCount()).isEqualTo(2);
        assertThat(metric.convertedCount()).isEqualTo(2);
        assertThat(metric.conversionRate()).isEqualTo(100.0);
    }

    @Test
    void 발송_전과_sentAt_정확히_120분_완료를_제외하고_SENT가_아닌_상태는_분모에_넣지_않는다() {
        digestWithCompletedTodo("before", SENT_AT.minusSeconds(1), PushStatus.SENT);
        digestWithCompletedTodo("boundary", SENT_AT.plusSeconds(120 * 60), PushStatus.SENT);
        digestWithCompletedTodo("pending", SENT_AT.plusSeconds(30 * 60), PushStatus.PENDING);
        digestWithCompletedTodo("blocked", SENT_AT.plusSeconds(30 * 60), PushStatus.BLOCKED);
        digestWithCompletedTodo("failed", SENT_AT.plusSeconds(30 * 60), PushStatus.FAILED);
        digestRepository.flush();
        entityManager.clear();

        DayMetric metric = metricsService.getMetrics(2).days().stream()
                .filter(day -> day.date().equals(DIGEST_DATE))
                .findFirst()
                .orElseThrow();

        assertThat(metric.createdCount()).isEqualTo(5);
        assertThat(metric.pendingCount()).isEqualTo(1);
        assertThat(metric.sentCount()).isEqualTo(2);
        assertThat(metric.blockedCount()).isEqualTo(1);
        assertThat(metric.failedCount()).isEqualTo(1);
        assertThat(metric.conversionMeasuredCount()).isEqualTo(2);
        assertThat(metric.convertedCount()).isZero();
    }

    @Test
    void 자정을_넘겨_발송된_digest도_sentAt_후_119분_완료를_전환으로_집계한다() {
        User user = userRepository.save(User.signUp("digest-midnight@rougether.dev"));
        Todo todo = Todo.create(user, null, "투두", null, DIGEST_DATE, null);
        Instant crossedMidnightSentAt = DIGEST_DATE.plusDays(1).atStartOfDay(KST).toInstant().plusSeconds(30);
        todo.complete(CurrencyType.COIN, 10, crossedMidnightSentAt.plusSeconds(119 * 60));
        todo = todoRepository.save(todo);
        DailyIncompleteDigest digest = saveDigest(user, null, todo.getId());
        digestRepository.flush();
        setDispatchState(digest, PushStatus.SENT, crossedMidnightSentAt, crossedMidnightSentAt);
        entityManager.clear();

        DayMetric metric = metricsService.getMetrics(2).days().stream()
                .filter(day -> day.date().equals(DIGEST_DATE))
                .findFirst()
                .orElseThrow();

        assertThat(metric.conversionMeasuredCount()).isEqualTo(1);
        assertThat(metric.convertedCount()).isEqualTo(1);
    }

    private DailyIncompleteDigest digestWithCompletedTodo(
            String suffix, Instant completedAt, PushStatus pushStatus) {
        User user = userRepository.save(User.signUp("digest-" + suffix + "@rougether.dev"));
        Todo todo = Todo.create(user, null, "투두", null, DIGEST_DATE, null);
        todo.complete(CurrencyType.COIN, 10, completedAt);
        todo = todoRepository.save(todo);
        DailyIncompleteDigest digest = saveDigest(user, null, todo.getId());
        digestRepository.flush();
        setDispatchState(digest, pushStatus);
        return digest;
    }

    private void setDispatchState(DailyIncompleteDigest digest, PushStatus pushStatus) {
        setDispatchState(digest, pushStatus, CREATED_AT, pushStatus == PushStatus.SENT ? SENT_AT : null);
    }

    private void setDispatchState(
            DailyIncompleteDigest digest, PushStatus pushStatus, Instant createdAt, Instant sentAt) {
        jdbcTemplate.update(
                "UPDATE daily_incomplete_digests SET push_status = ?, sent_at = ?, created_at = ? WHERE id = ?",
                pushStatus.name(), sentAt == null ? null : Timestamp.from(sentAt),
                Timestamp.from(createdAt), digest.getId());
    }

    private DailyIncompleteDigest saveDigest(User user, Long routineLineageId, Long todoId) {
        DailyIncompleteDigest digest = digestRepository.save(
                DailyIncompleteDigest.create(
                        user, DIGEST_DATE, routineLineageId == null ? 0 : 1, todoId == null ? 0 : 1));
        Notification notification = notificationRepository.save(Notification.create(
                user,
                NotificationType.DAILY_INCOMPLETE_DIGEST,
                "오늘의 루틴을 마무리해 볼까요?",
                "오늘 아직 할 일이 남았어요.",
                digest.getId()));
        digest.linkNotification(notification);
        digestRepository.save(digest);
        if (routineLineageId != null) {
            targetRepository.save(DailyIncompleteDigestTarget.routine(digest, routineLineageId));
        }
        if (todoId != null) {
            targetRepository.save(DailyIncompleteDigestTarget.todo(digest, todoId));
        }
        return digest;
    }
}
