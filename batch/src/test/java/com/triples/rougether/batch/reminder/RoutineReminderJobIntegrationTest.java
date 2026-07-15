package com.triples.rougether.batch.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.batch.config.BatchJdbcConfig;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.DevicePlatform;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.entity.PushStatus;
import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.infra.fcm.FcmSendResult;
import com.triples.rougether.infra.fcm.FcmSender;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.stereotype.Component;

@SpringBootTest(classes = RoutineReminderJobIntegrationTest.TestConfig.class)
@SpringBatchTest
class RoutineReminderJobIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.triples.rougether.domain")
    @EnableJpaRepositories("com.triples.rougether.domain")
    @EnableJpaAuditing
    @Import({BatchJdbcConfig.class, RoutineReminderJobConfig.class, ReminderPushWriter.class})
    static class TestConfig {

        @Bean
        FcmSender fcmSender() {
            return new TestFcmSender();
        }
    }

    // 테스트가 발송 결과를 마음대로 제어하기 위한 대역 - StubFcmSender는 항상 successCount=0 이라
    // SENT 전이·무효토큰 삭제 경로를 검증할 수 없음
    static class TestFcmSender implements FcmSender {
        FcmSendResult nextResult = new FcmSendResult(1, List.of());
        List<List<String>> calls = new ArrayList<>();

        @Override
        public FcmSendResult send(List<String> tokens, String title, String body) {
            calls.add(tokens);
            return nextResult;
        }
    }

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;
    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private RoutineLogRepository routineLogRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserDeviceTokenRepository userDeviceTokenRepository;
    @Autowired
    private TestFcmSender testFcmSender;

    @AfterEach
    void cleanUp() {
        // Step2 reader가 날짜 무관 전체 PENDING을 훑으므로 테스트 간 알림이 남으면 서로 간섭함
        notificationRepository.deleteAll();
        routineLogRepository.deleteAll();
        userDeviceTokenRepository.deleteAll();
        routineRepository.deleteAll();
        userRepository.deleteAll();
        testFcmSender.nextResult = new FcmSendResult(1, List.of());
        testFcmSender.calls.clear();
    }

    @Test
    void 반복요일에_포함되면_리마인드_알림을_생성한다() throws Exception {
        LocalDate date = LocalDate.of(2026, 1, 5); // 월요일
        User user = userRepository.save(User.signUp());
        Routine routine = persistRoutine(user, "스트레칭", "WEEKLY", weekdaysJson("MON"), LocalTime.of(9, 0), null, null);

        runJob(date, LocalTime.of(9, 0));

        List<Notification> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getRefId()).isEqualTo(routine.getId());
        assertThat(notifications.get(0).getType()).isEqualTo(NotificationType.ROUTINE_REMINDER);
    }

    @Test
    void 오늘_요일이_반복요일에_없으면_대상에서_제외한다() throws Exception {
        LocalDate date = LocalDate.of(2026, 1, 6); // 화요일
        User user = userRepository.save(User.signUp());
        persistRoutine(user, "스트레칭", "WEEKLY", weekdaysJson("MON"), LocalTime.of(9, 0), null, null);

        runJob(date, LocalTime.of(9, 0));

        assertThat(notificationRepository.findAll()).isEmpty();
    }

    @Test
    void 유효기간_범위_밖이면_대상에서_제외한다() throws Exception {
        LocalDate date = LocalDate.of(2026, 1, 7);
        User user = userRepository.save(User.signUp());
        persistRoutine(user, "아침 운동", "DAILY", null, LocalTime.of(9, 0), date.plusDays(1), null);

        runJob(date, LocalTime.of(9, 0));

        assertThat(notificationRepository.findAll()).isEmpty();
    }

    @Test
    void 오늘_이미_완료한_루틴은_제외한다() throws Exception {
        LocalDate date = LocalDate.of(2026, 1, 8);
        User user = userRepository.save(User.signUp());
        Routine routine = persistRoutine(user, "아침 운동", "DAILY", null, LocalTime.of(9, 0), null, null);
        routineLogRepository.save(RoutineLog.complete(routine, date, Instant.now(), CurrencyType.COIN, 0));

        runJob(date, LocalTime.of(9, 0));

        assertThat(notificationRepository.findAll()).isEmpty();
    }

    @Test
    void 오늘_이미_발송된_루틴은_제외한다() throws Exception {
        // 기발송 판정은 createdAt(auditing, 실제 현재 시각) 기준 당일 윈도우라 date도 실제 오늘이어야 함
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Seoul"));
        User user = userRepository.save(User.signUp());
        Routine routine = persistRoutine(user, "아침 운동", "DAILY", null, LocalTime.of(9, 0), null, null);
        notificationRepository.save(Notification.create(user, NotificationType.ROUTINE_REMINDER,
                ReminderMessage.TITLE, ReminderMessage.body("아침 운동"), routine.getId()));

        runJob(date, LocalTime.of(9, 0));

        // Step1이 새로 만든 알림은 없어야 함(기존 1건만 유지)
        assertThat(notificationRepository.findAll()).hasSize(1);
    }

    @Test
    void 삭제된_루틴은_제외한다() throws Exception {
        LocalDate date = LocalDate.of(2026, 1, 10);
        User user = userRepository.save(User.signUp());
        Routine routine = persistRoutine(user, "아침 운동", "DAILY", null, LocalTime.of(9, 0), null, null);
        routine.softDelete(Instant.now());
        routineRepository.save(routine);

        runJob(date, LocalTime.of(9, 0));

        assertThat(notificationRepository.findAll()).isEmpty();
    }

    @Test
    void 페이지_크기를_초과하는_대상도_모두_처리한다() throws Exception {
        // reader가 페이지(200)마다 offset을 그대로 밀면, 처리된 항목이 필터에서 즉시 빠지는 쿼리
        // 특성상 뒷 구간이 통째로 스킵된다 - 200을 넘는 건수로 그 회귀를 잡는다
        LocalDate date = LocalDate.of(2026, 1, 20);
        int count = 250;
        User user = userRepository.save(User.signUp());
        for (int i = 0; i < count; i++) {
            persistRoutine(user, "루틴" + i, "DAILY", null, LocalTime.of(9, 0), null, null);
        }

        runJob(date, LocalTime.of(9, 0));

        List<Notification> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(count);
        assertThat(notifications).allMatch(n -> n.getPushStatus() != PushStatus.PENDING);
    }

    @Test
    void 같은_분_재실행은_JobInstance_유일성으로_막힌다() throws Exception {
        LocalDate date = LocalDate.of(2026, 1, 11);
        JobParameters params = targetMinuteParams(date, LocalTime.of(9, 0));

        jobLauncherTestUtils.launchJob(params);

        assertThatThrownBy(() -> jobLauncherTestUtils.launchJob(params))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @Test
    void 토큰이_있으면_발송하고_SENT로_전이한다() throws Exception {
        User user = userRepository.save(User.signUp());
        userDeviceTokenRepository.save(UserDeviceToken.register(user, "token-1", DevicePlatform.ANDROID, Instant.now()));
        Notification notification = notificationRepository.save(Notification.create(user,
                NotificationType.ROUTINE_REMINDER, ReminderMessage.TITLE, ReminderMessage.body("아침 운동"), 1L));
        testFcmSender.nextResult = new FcmSendResult(1, List.of());

        jobLauncherTestUtils.launchStep("routineReminderPushStep");

        Notification updated = notificationRepository.findById(notification.getId()).orElseThrow();
        assertThat(updated.getPushStatus()).isEqualTo(PushStatus.SENT);
        assertThat(testFcmSender.calls).hasSize(1);
    }

    @Test
    void 무효_토큰은_삭제되고_FAILED로_전이한다() throws Exception {
        User user = userRepository.save(User.signUp());
        UserDeviceToken token = userDeviceTokenRepository.save(
                UserDeviceToken.register(user, "invalid-token", DevicePlatform.IOS, Instant.now()));
        Notification notification = notificationRepository.save(Notification.create(user,
                NotificationType.ROUTINE_REMINDER, ReminderMessage.TITLE, ReminderMessage.body("아침 운동"), 1L));
        testFcmSender.nextResult = new FcmSendResult(0, List.of("invalid-token"));

        jobLauncherTestUtils.launchStep("routineReminderPushStep");

        Notification updated = notificationRepository.findById(notification.getId()).orElseThrow();
        assertThat(updated.getPushStatus()).isEqualTo(PushStatus.FAILED);
        Optional<UserDeviceToken> remaining = userDeviceTokenRepository.findByToken("invalid-token");
        assertThat(remaining).isEmpty();
        assertThat(token.getId()).isNotNull();
    }

    private void runJob(LocalDate date, LocalTime time) throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(targetMinuteParams(date, time));
        assertThat(execution.getStatus()).isEqualTo(org.springframework.batch.core.BatchStatus.COMPLETED);
    }

    private JobParameters targetMinuteParams(LocalDate date, LocalTime time) {
        String targetMinute = LocalDateTime.of(date, time).format(RoutineReminderJobConfig.TARGET_MINUTE_FORMAT);
        return new JobParametersBuilder()
                .addString(RoutineReminderJobConfig.TARGET_MINUTE_PARAM, targetMinute)
                .toJobParameters();
    }

    private Routine persistRoutine(User user, String title, String repeatType, String repeatDays,
                                   LocalTime scheduledTime, LocalDate startsOn, LocalDate endsOn) {
        return routineRepository.save(Routine.create(user, null, title, AuthType.CHECK,
                repeatType, repeatDays, scheduledTime, startsOn, endsOn));
    }

    private String weekdaysJson(String... tokens) {
        StringBuilder sb = new StringBuilder("{\"daysOfWeek\":[");
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(tokens[i]).append('"');
        }
        return sb.append("]}").toString();
    }
}
