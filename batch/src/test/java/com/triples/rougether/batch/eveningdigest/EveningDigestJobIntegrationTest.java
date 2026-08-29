package com.triples.rougether.batch.eveningdigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.triples.rougether.batch.config.BatchJdbcConfig;
import com.triples.rougether.batch.reminder.ReminderPushWriter;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.digest.entity.DailyIncompleteDigest;
import com.triples.rougether.domain.notification.digest.entity.DailyIncompleteDigestTarget;
import com.triples.rougether.domain.notification.digest.entity.DailyIncompleteDigestTargetType;
import com.triples.rougether.domain.notification.digest.repository.DailyIncompleteDigestRepository;
import com.triples.rougether.domain.notification.digest.repository.DailyIncompleteDigestTargetRepository;
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
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.infra.fcm.FcmSendResult;
import com.triples.rougether.infra.fcm.FcmSender;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(
        classes = EveningDigestJobIntegrationTest.TestConfig.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:evening-digest;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration",
                "spring.batch.job.enabled=false"
        })
@SpringBatchTest
class EveningDigestJobIntegrationTest {

    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 8, 31); // 월요일

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.triples.rougether.domain")
    @EnableJpaRepositories("com.triples.rougether.domain")
    @EnableJpaAuditing
    @Import({BatchJdbcConfig.class, EveningDigestJobConfig.class, ReminderPushWriter.class})
    static class TestConfig {

        @Bean
        FcmSender fcmSender() {
            return new TestFcmSender();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(TARGET_DATE.atTime(LocalTime.of(22, 0))
                    .atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));
        }

    }

    static class TestFcmSender implements FcmSender {

        record Call(List<String> tokens, String title, String body) {
        }

        final List<Call> calls = new ArrayList<>();

        @Override
        public FcmSendResult send(List<String> tokens, String title, String body) {
            calls.add(new Call(List.copyOf(tokens), title, body));
            if (tokens.contains("throw-token")) {
                throw new IllegalStateException("사용자별 FCM 오류");
            }
            return new FcmSendResult(1, List.of());
        }
    }

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private RoutineLogRepository routineLogRepository;
    @Autowired
    private TodoRepository todoRepository;
    @Autowired
    private DailyIncompleteDigestRepository digestRepository;
    @Autowired
    private DailyIncompleteDigestTargetRepository targetRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @MockitoSpyBean
    private NotificationSettingRepository notificationSettingRepository;
    @Autowired
    private UserDeviceTokenRepository userDeviceTokenRepository;
    @Autowired
    private TestFcmSender fcmSender;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private Clock clock;

    @BeforeEach
    void createBatchSequencesForH2() {
        jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS BATCH_JOB_INSTANCE_SEQ START WITH 1");
        jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS BATCH_JOB_EXECUTION_SEQ START WITH 1");
        jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS BATCH_STEP_EXECUTION_SEQ START WITH 1");
    }

    @AfterEach
    void cleanUp() {
        targetRepository.deleteAll();
        digestRepository.deleteAll();
        notificationRepository.deleteAll();
        notificationSettingRepository.deleteAll();
        userDeviceTokenRepository.deleteAll();
        routineLogRepository.deleteAll();
        todoRepository.deleteAll();
        routineRepository.deleteAll();
        userRepository.deleteAll();
        fcmSender.calls.clear();
    }

    @Test
    void 오늘의_미완료_루틴과_투두를_한_건으로_스냅숏하고_push한다() throws Exception {
        User user = humanWithToken("good-token");
        Routine incomplete = routine(user, "DAILY", null);
        Routine completed = routine(user, "DAILY", null);
        routineLogRepository.save(RoutineLog.complete(
                completed, TARGET_DATE, Instant.parse("2026-08-31T09:00:00Z"), CurrencyType.COIN, 10));
        routine(user, "WEEKLY", "{\"daysOfWeek\":[\"TUE\"]}");
        Todo pending = todo(user, TARGET_DATE);
        Todo done = todo(user, TARGET_DATE);
        done.complete(CurrencyType.COIN, 10, Instant.parse("2026-08-31T09:00:00Z"));
        todoRepository.save(done);
        todo(user, TARGET_DATE.plusDays(1));

        runJob();

        DailyIncompleteDigest digest = digestRepository.findByUserIdAndDigestDate(user.getId(), TARGET_DATE)
                .orElseThrow();
        assertThat(digest.getRoutineCount()).isEqualTo(1);
        assertThat(digest.getTodoCount()).isEqualTo(1);
        assertThat(digest.getPushStatus()).isEqualTo(PushStatus.SENT);
        assertThat(digest.getSentAt()).isEqualTo(clock.instant());
        assertThat(targetRepository.findAllByDigestIdOrderByIdAsc(digest.getId()))
                .extracting(target -> target.getTargetType() + ":" + target.getTargetId())
                .containsExactlyInAnyOrder(
                        DailyIncompleteDigestTargetType.ROUTINE + ":" + incomplete.getOriginRoutineId(),
                        DailyIncompleteDigestTargetType.TODO + ":" + pending.getId());

        List<Notification> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(1);
        Notification notification = notifications.getFirst();
        assertThat(notification.getType()).isEqualTo(NotificationType.DAILY_INCOMPLETE_DIGEST);
        assertThat(notification.getRefId()).isEqualTo(digest.getId());
        assertThat(notification.getPushStatus()).isEqualTo(PushStatus.SENT);
        assertThat(notification.getBody())
                .isEqualTo("오늘 아직 2개가 남았어요. 루틴 1개 · 투두 1개를 마무리해볼까요?");
        assertThat(fcmSender.calls).singleElement()
                .satisfies(call -> assertThat(call.tokens()).containsExactly("good-token"));
    }

    @Test
    void 같은_날짜를_재실행해도_사용자당_한_건만_남긴다() throws Exception {
        User user = humanWithToken("dedupe-token");
        routine(user, "DAILY", null);

        runJob();
        runJob();

        assertThat(digestRepository.findAll()).hasSize(1);
        assertThat(notificationRepository.findAll()).hasSize(1);
        assertThat(fcmSender.calls).hasSize(1);
    }

    @Test
    void 이전_날짜에_남은_PENDING은_오늘_발송에_섞이지_않는다() throws Exception {
        User staleUser = humanWithToken("stale-token");
        DailyIncompleteDigest staleDigest = digestRepository.save(
                DailyIncompleteDigest.create(staleUser, TARGET_DATE.minusDays(1), 0, 1));
        Notification staleNotification = notificationRepository.save(Notification.create(
                staleUser,
                NotificationType.DAILY_INCOMPLETE_DIGEST,
                EveningDigestMessage.TITLE,
                EveningDigestMessage.body(0, 1),
                staleDigest.getId()));
        staleDigest.linkNotification(staleNotification);
        digestRepository.save(staleDigest);
        targetRepository.save(DailyIncompleteDigestTarget.todo(staleDigest, 999L));

        User todayUser = humanWithToken("today-token");
        todo(todayUser, TARGET_DATE);

        runJob();

        assertThat(notificationRepository.findById(staleNotification.getId()).orElseThrow().getPushStatus())
                .isEqualTo(PushStatus.PENDING);
        assertThat(digestRepository.findById(staleDigest.getId()).orElseThrow().getPushStatus())
                .isEqualTo(PushStatus.PENDING);
        assertThat(fcmSender.calls)
                .singleElement()
                .satisfies(call -> assertThat(call.tokens()).containsExactly("today-token"));
    }

    @Test
    void 이전_버전에서_오늘_완료한_루틴_계보는_미완료로_세지_않는다() throws Exception {
        User user = humanWithToken("lineage-token");
        Routine root = routine(user, "DAILY", null);
        routineLogRepository.save(RoutineLog.complete(
                root, TARGET_DATE, Instant.parse("2026-08-31T09:00:00Z"), CurrencyType.COIN, 10));
        root.softDelete(Instant.parse("2026-08-31T10:00:00Z"));
        routineRepository.save(root);
        routineRepository.save(root.copyAsNewVersion(
                null, "수정 루틴", AuthType.CHECK, "DAILY", null, null,
                TARGET_DATE.minusDays(1), null));

        runJob();

        assertThat(digestRepository.findByUserIdAndDigestDate(user.getId(), TARGET_DATE)).isEmpty();
        assertThat(notificationRepository.findAll()).isEmpty();
        assertThat(fcmSender.calls).isEmpty();
    }

    @Test
    void REMINDER와_ALL을_끈_사용자는_FCM없이_BLOCKED로_남긴다() throws Exception {
        User reminderOff = humanWithToken("reminder-off");
        notificationSettingRepository.save(
                NotificationSetting.create(reminderOff, NotificationSettingType.REMINDER, false));
        todo(reminderOff, TARGET_DATE);
        User allOff = humanWithToken("all-off");
        notificationSettingRepository.save(NotificationSetting.create(allOff, NotificationSettingType.ALL, false));
        todo(allOff, TARGET_DATE);

        runJob();

        assertThat(digestRepository.findAll())
                .extracting(DailyIncompleteDigest::getPushStatus)
                .containsExactlyInAnyOrder(PushStatus.BLOCKED, PushStatus.BLOCKED);
        assertThat(notificationRepository.findAll())
                .extracting(Notification::getPushStatus)
                .containsOnly(PushStatus.BLOCKED);
        assertThat(fcmSender.calls).isEmpty();
    }

    @Test
    void 한_사용자의_FCM_오류가_다른_사용자의_발송을_막지_않는다() throws Exception {
        User broken = humanWithToken("throw-token");
        todo(broken, TARGET_DATE);
        User healthy = humanWithToken("healthy-token");
        todo(healthy, TARGET_DATE);

        runJob();

        assertThat(digestRepository.findByUserIdAndDigestDate(broken.getId(), TARGET_DATE).orElseThrow()
                .getPushStatus()).isEqualTo(PushStatus.FAILED);
        assertThat(digestRepository.findByUserIdAndDigestDate(healthy.getId(), TARGET_DATE).orElseThrow()
                .getPushStatus()).isEqualTo(PushStatus.SENT);
        assertThat(fcmSender.calls).hasSize(2);
    }

    @Test
    void push_인프라_예외는_job을_FAILED로_남기고_같은_parameter_재시작으로_PENDING을_복구한다()
            throws Exception {
        User user = humanWithToken("restart-token");
        todo(user, TARGET_DATE);
        JobParameters parameters = jobParameters("push-infra-restart");
        doThrow(new DataAccessResourceFailureException("notification setting repository unavailable"))
                .when(notificationSettingRepository).findAllByUserIdIn(anySet());

        JobExecution failed;
        try {
            failed = jobOperatorTestUtils.startJob(parameters);
        } finally {
            reset(notificationSettingRepository);
        }

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        DailyIncompleteDigest pendingDigest = digestRepository
                .findByUserIdAndDigestDate(user.getId(), TARGET_DATE)
                .orElseThrow();
        assertThat(pendingDigest.getPushStatus()).isEqualTo(PushStatus.PENDING);
        assertThat(notificationRepository.findAll())
                .singleElement()
                .extracting(Notification::getPushStatus)
                .isEqualTo(PushStatus.PENDING);
        assertThat(fcmSender.calls).isEmpty();

        JobExecution restarted = jobOperatorTestUtils.startJob(parameters);

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(digestRepository.findById(pendingDigest.getId()).orElseThrow().getPushStatus())
                .isEqualTo(PushStatus.SENT);
        assertThat(notificationRepository.findAll())
                .singleElement()
                .extracting(Notification::getPushStatus)
                .isEqualTo(PushStatus.SENT);
        assertThat(fcmSender.calls).hasSize(1);
    }

    @Test
    void 봇과_탈퇴_사용자와_미완료가_없는_사용자는_제외한다() throws Exception {
        User bot = userRepository.save(User.bot("digest-bot", "봇", "테스트"));
        todo(bot, TARGET_DATE);
        User withdrawn = humanWithToken("withdrawn-token");
        todo(withdrawn, TARGET_DATE);
        withdrawn.softDelete(Instant.now());
        userRepository.save(withdrawn);
        User completedOnly = humanWithToken("completed-token");
        Todo completedTodo = todo(completedOnly, TARGET_DATE);
        completedTodo.complete(CurrencyType.COIN, 10, Instant.now());
        todoRepository.save(completedTodo);

        runJob();

        assertThat(digestRepository.findAll()).isEmpty();
        assertThat(notificationRepository.findAll()).isEmpty();
        assertThat(fcmSender.calls).isEmpty();
    }

    private void runJob() throws Exception {
        JobExecution execution = jobOperatorTestUtils.startJob(jobParameters(UUID.randomUUID().toString()));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    private JobParameters jobParameters(String run) {
        return new JobParametersBuilder()
                .addString(EveningDigestJobConfig.TARGET_DATE_PARAM, TARGET_DATE.toString())
                .addString("run", run)
                .toJobParameters();
    }

    private User humanWithToken(String token) {
        User user = userRepository.save(User.signUp());
        userDeviceTokenRepository.save(UserDeviceToken.register(user, token, DevicePlatform.ANDROID, Instant.now()));
        return user;
    }

    private Routine routine(User user, String repeatType, String repeatDays) {
        Routine routine = routineRepository.save(Routine.create(
                user, null, "루틴", AuthType.CHECK, repeatType, repeatDays, null, TARGET_DATE.minusDays(1), null));
        routine.assignOriginToSelf();
        return routineRepository.save(routine);
    }

    private Todo todo(User user, LocalDate dueDate) {
        return todoRepository.save(Todo.create(user, null, "투두", null, dueDate, null));
    }
}
