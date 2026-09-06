package com.triples.rougether.batch.appicon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.batch.reminder.ReminderPushWriter;
import com.triples.rougether.domain.appicon.AppIconPolicy;
import com.triples.rougether.domain.appicon.entity.UserAppActivity;
import com.triples.rougether.domain.appicon.repository.UserAppActivityRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.DevicePlatform;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationSetting;
import com.triples.rougether.domain.notification.entity.NotificationSettingType;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.entity.PushStatus;
import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.notification.repository.NotificationSettingRepository;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.infra.fcm.FcmSendResult;
import com.triples.rougether.infra.fcm.FcmSender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

// 실제 MySQL의 사용자 잠금·커서 조회·롤백을 검증하고 FCM 전송만 mock 처리함.
@SpringBootTest(classes = AppIconReminderIntegrationTest.TestConfig.class)
class AppIconReminderIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-06T03:00:00Z"); // 12시 KST

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.triples.rougether.domain")
    @EnableJpaRepositories("com.triples.rougether.domain")
    @EnableJpaAuditing
    @Import({AppIconReminderService.class, ReminderPushWriter.class})
    static class TestConfig {
    }

    @Autowired private AppIconReminderService service;
    @Autowired private UserRepository users;
    @Autowired private UserAppActivityRepository activities;
    @Autowired private NotificationRepository notifications;
    @Autowired private NotificationSettingRepository settings;
    @Autowired private UserDeviceTokenRepository deviceTokens;
    @Autowired private TodoRepository todos;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private Clock clock;
    @MockitoBean private FcmSender fcm;

    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(AppIconPolicy.KST);
        when(fcm.send(anyList(), anyString(), anyString())).thenReturn(new FcmSendResult(1, List.of()));
    }

    @AfterEach
    void cleanup() {
        for (Long userId : userIds) {
            jdbc.update("delete from user_app_activity where user_id = ?", userId);
            jdbc.update("delete from notification where user_id = ?", userId);
            jdbc.update("delete from notification_setting where user_id = ?", userId);
            jdbc.update("delete from user_device_token where user_id = ?", userId);
            jdbc.update("delete from todos where user_id = ?", userId);
            jdbc.update("delete from users where id = ?", userId);
        }
    }

    @Test
    void 단계마다_한번만_적재하며_반복_실행해도_FCM은_다시_보내지_않는다() {
        User user = inactive(Duration.ofHours(48));
        service.stage(user.getId());
        service.stage(user.getId());
        Notification first = latest(user);
        assertThat(first.getTitle()).isEqualTo("고양이가 기다린다냥");
        assertThat(first.getRefId()).isEqualTo(user.getId());
        service.sendPending(user.getId(), first.getId());
        service.sendPending(user.getId(), first.getId());

        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(2)));
        service.stage(user.getId());
        assertThat(latest(user).getTitle()).isEqualTo("보고 싶다냥…");
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(5)));
        service.stage(user.getId());
        assertThat(latest(user).getTitle()).isEqualTo("언제 돌아오냥…");
        service.stage(user.getId());
        assertThat(notificationCount(user)).isEqualTo(3);
        verify(fcm, times(1)).send(anyList(), anyString(), anyString());
    }

    @Test
    void 오래_비운_사용자는_현재_단계_하나만_받는다() {
        User user = inactive(Duration.ofDays(30));
        service.stage(user.getId());
        assertThat(notificationCount(user)).isEqualTo(1);
        assertThat(latest(user).getTitle()).isEqualTo("언제 돌아오냥…");
        assertThat(activities.findById(user.getId()).orElseThrow().getLastNotifiedStage()).isEqualTo(3);
    }

    @Test
    void 방문_후_이전_PENDING은_차단되고_새_미접속_주기는_다시_알린다() {
        User user = inactive(Duration.ofDays(3));
        service.stage(user.getId());
        Long oldId = latest(user).getId();
        foreground(user, NOW);
        service.sendPending(user.getId(), oldId);
        assertThat(notifications.findById(oldId).orElseThrow().getPushStatus()).isEqualTo(PushStatus.BLOCKED);
        verify(fcm, never()).send(anyList(), anyString(), anyString());

        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(2)));
        service.stage(user.getId());
        assertThat(notificationCount(user)).isEqualTo(2);
        Long newId = latest(user).getId();
        assertThat(newId).isNotEqualTo(oldId);
        service.sendPending(user.getId(), newId);
        assertThat(notifications.findById(newId).orElseThrow().getPushStatus()).isEqualTo(PushStatus.SENT);
    }

    @Test
    void 이전_단계의_PENDING도_최신_단계가_적재되면_차단한다() {
        User user = inactive(Duration.ofDays(2));
        service.stage(user.getId());
        Long oldId = latest(user).getId();
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(5)));
        service.stage(user.getId());
        service.sendPending(user.getId(), oldId);
        assertThat(notifications.findById(oldId).orElseThrow().getPushStatus()).isEqualTo(PushStatus.BLOCKED);
        service.sendPending(user.getId(), latest(user).getId());
        verify(fcm, times(1)).send(anyList(), anyString(), anyString());
    }

    @ParameterizedTest
    @EnumSource(value = NotificationSettingType.class, names = {"ALL", "REMINDER"})
    void 설정_OFF면_내역은_남기고_FCM만_차단한다(NotificationSettingType type) {
        User user = inactive(Duration.ofDays(3));
        settings.save(NotificationSetting.create(user, type, false));
        service.stage(user.getId());
        Notification notification = latest(user);
        service.sendPending(user.getId(), notification.getId());
        assertThat(notificationCount(user)).isEqualTo(1);
        assertThat(notifications.findById(notification.getId()).orElseThrow().getPushStatus())
                .isEqualTo(PushStatus.BLOCKED);
        verify(fcm, never()).send(anyList(), anyString(), anyString());
    }

    @Test
    void 밤에는_적재하지_않고_적재된_PENDING도_낮까지_보류한다() {
        User user = inactive(Duration.ofDays(3));
        when(clock.instant()).thenReturn(Instant.parse("2026-09-05T23:59:59Z")); // 08:59:59
        service.stage(user.getId());
        assertThat(notificationCount(user)).isZero();

        when(clock.instant()).thenReturn(Instant.parse("2026-09-06T00:00:00Z")); // 09:00
        service.stage(user.getId());
        Long id = latest(user).getId();
        when(clock.instant()).thenReturn(Instant.parse("2026-09-06T12:00:00Z")); // 21:00
        service.sendPending(user.getId(), id);
        assertThat(notifications.findById(id).orElseThrow().getPushStatus()).isEqualTo(PushStatus.PENDING);
        verify(fcm, never()).send(anyList(), anyString(), anyString());
    }

    @Test
    void 활동_기록이_없는_사용자와_봇과_탈퇴자는_제외한다() {
        User legacy = human();
        User bot = users.save(User.bot("app-icon-reminder-bot", "고양이 봇", "봇"));
        userIds.add(bot.getId());
        saveActivity(bot, NOW.minus(Duration.ofDays(10)));
        User withdrawn = inactive(Duration.ofDays(10));
        withdrawn.softDelete(NOW);
        users.save(withdrawn);

        for (User user : List.of(legacy, bot, withdrawn)) {
            service.stage(user.getId());
            assertThat(notificationCount(user)).isZero();
        }
        assertThat(candidates(0, 100)).doesNotContain(legacy.getId(), bot.getId(), withdrawn.getId());
    }

    @Test
    void 오늘_실천했으면_슬픈_알림을_보내지_않는다() {
        User user = inactive(Duration.ofDays(8));
        service.stage(user.getId());
        Long id = latest(user).getId();
        Todo todo = Todo.create(user, null, "책 읽기", null, null, null);
        todo.complete(CurrencyType.COIN, 0, NOW);
        todos.save(todo);
        service.sendPending(user.getId(), id);
        service.stage(user.getId());
        assertThat(notificationCount(user)).isEqualTo(1);
        assertThat(notifications.findById(id).orElseThrow().getPushStatus()).isEqualTo(PushStatus.BLOCKED);
        verify(fcm, never()).send(anyList(), anyString(), anyString());
    }

    @Test
    void 알림_적재_롤백은_단계도_롤백해서_다시_시도할_수_있다() {
        User user = inactive(Duration.ofDays(3));
        tx.executeWithoutResult(status -> {
            service.stage(user.getId());
            status.setRollbackOnly();
        });
        assertThat(notificationCount(user)).isZero();
        assertThat(activities.findById(user.getId()).orElseThrow().getLastNotifiedStage()).isZero();
        service.stage(user.getId());
        assertThat(notificationCount(user)).isEqualTo(1);
    }

    @Test
    void 동시에_적재하고_발송해도_한_단계_한_번만_처리한다() throws Exception {
        User user = inactive(Duration.ofDays(3));
        parallel(() -> service.stage(user.getId()));
        assertThat(notificationCount(user)).isEqualTo(1);
        Long id = latest(user).getId();
        parallel(() -> service.sendPending(user.getId(), id));
        verify(fcm, times(1)).send(anyList(), anyString(), anyString());
    }

    @Test
    void 발송_실패도_내역과_실패상태를_남기며_같은_단계를_재적재하지_않는다() {
        User user = inactive(Duration.ofDays(3));
        when(fcm.send(anyList(), anyString(), anyString())).thenThrow(new IllegalStateException("FCM test"));
        service.stage(user.getId());
        Long id = latest(user).getId();
        service.sendPending(user.getId(), id);
        service.stage(user.getId());
        assertThat(notificationCount(user)).isEqualTo(1);
        assertThat(notifications.findById(id).orElseThrow().getPushStatus()).isEqualTo(PushStatus.FAILED);
    }

    @Test
    void 후보_처리로_결과가_줄어도_커서_다음_사용자를_놓치지_않는다() {
        User first = inactive(Duration.ofDays(2));
        User second = inactive(Duration.ofDays(4));
        User third = inactive(Duration.ofDays(7));
        User recent = inactive(Duration.ofHours(47));
        assertThat(candidates(0, 2)).containsExactly(first.getId(), second.getId());
        service.stage(first.getId());
        service.stage(second.getId());
        assertThat(candidates(second.getId(), 2)).containsExactly(third.getId());
        assertThat(candidates(third.getId(), 2)).doesNotContain(recent.getId());
    }

    @Test
    void 트리거가_현재_후보를_적재하고_기존_FCM_경로로_보낸다() {
        User user = inactive(Duration.ofDays(3));
        AppIconReminderTrigger trigger = new AppIconReminderTrigger(activities, notifications, service, clock);
        trigger.run();
        trigger.run();
        assertThat(notificationCount(user)).isEqualTo(1);
        assertThat(latest(user).getPushStatus()).isEqualTo(PushStatus.SENT);
        verify(fcm, times(1)).send(anyList(), anyString(), anyString());
    }

    private List<Long> candidates(long after, int size) {
        return activities.findReminderCandidates(after, NOW.minus(Duration.ofDays(2)),
                NOW.minus(Duration.ofDays(4)), NOW.minus(Duration.ofDays(7)), PageRequest.of(0, size));
    }

    private User human() {
        User user = users.save(User.signUp());
        userIds.add(user.getId());
        return user;
    }

    private User inactive(Duration duration) {
        User user = human();
        saveActivity(user, NOW.minus(duration));
        deviceTokens.save(UserDeviceToken.register(user, "test-icon-" + user.getId(), DevicePlatform.IOS, NOW));
        return user;
    }

    private void saveActivity(User user, Instant at) {
        tx.executeWithoutResult(ignored -> activities.save(UserAppActivity.start(
                users.findByIdForUpdate(user.getId()).orElseThrow(), at)));
    }

    private void foreground(User user, Instant at) {
        tx.executeWithoutResult(ignored -> {
            users.findByIdForUpdate(user.getId()).orElseThrow();
            activities.findById(user.getId()).orElseThrow().recordForeground(at);
        });
    }

    private Notification latest(User user) {
        return notifications.findPageByCursor(user.getId(), null, PageRequest.of(0, 1)).getFirst();
    }

    private long notificationCount(User user) {
        return jdbc.queryForObject("select count(*) from notification where user_id = ? and type = ?",
                Long.class, user.getId(), NotificationType.APP_INACTIVITY_REMINDER.name());
    }

    private void parallel(Runnable action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); action.run(); return null; });
            var second = executor.submit(() -> { start.await(); action.run(); return null; });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
    }
}
