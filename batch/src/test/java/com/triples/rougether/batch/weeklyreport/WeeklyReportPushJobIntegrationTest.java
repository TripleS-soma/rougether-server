package com.triples.rougether.batch.weeklyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.batch.config.BatchJdbcConfig;
import com.triples.rougether.batch.reminder.ReminderPushWriter;
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
import com.triples.rougether.domain.report.entity.WeeklyReport;
import com.triples.rougether.domain.report.repository.WeeklyReportRepository;
import com.triples.rougether.infra.fcm.FcmSendResult;
import com.triples.rougether.infra.fcm.FcmSender;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobOperatorTestUtils;
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

// spring.batch.job.enabled=false: 자동설정의 JobLauncherApplicationRunner 가 기동 시 빈 파라미터로 job 을 돌리면
// weekStart 검증기가 컨텍스트 로딩을 실패시키므로(운영 yml 과 동일하게) 끈다.
@SpringBootTest(classes = WeeklyReportPushJobIntegrationTest.TestConfig.class,
        properties = "spring.batch.job.enabled=false")
@SpringBatchTest
class WeeklyReportPushJobIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 트리거 기준 "지금": 2025-03-16(일) 20:00 KST → 대상 주 = 2025-03-09(일) ~ 2025-03-15(토).
    // 생성 job 통합 테스트와 같은 주지만 job 이름이 달라 Spring Batch 메타데이터(JobInstance)는 겹치지 않는다.
    private static final LocalDate TRIGGER_DAY = LocalDate.of(2025, 3, 16);
    private static final LocalDate WEEK_START = LocalDate.of(2025, 3, 9);
    private static final LocalDate WEEK_END = LocalDate.of(2025, 3, 15);
    private static final String EMPTY_SECTIONS_JSON = "{\"highlights\":[],\"failurePatterns\":[],\"suggestions\":[]}";

    // 트리거는 빈으로 올리지 않는다 - 기동 catch-up(ApplicationReadyEvent)이 고정 시계(발송 시각 이후) 때문에
    // 테스트 데이터가 생기기 전에 weekStart 인스턴스를 COMPLETED 로 소비해 버림. 트리거 테스트에서 직접 생성한다.
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.triples.rougether.domain")
    @EnableJpaRepositories("com.triples.rougether.domain")
    @EnableJpaAuditing
    @Import({BatchJdbcConfig.class, WeeklyReportPushJobConfig.class, WeeklyReportPushProcessor.class,
            ReminderPushWriter.class})
    static class TestConfig {

        @Bean
        Clock kstClock() {
            return Clock.fixed(ZonedDateTime.of(TRIGGER_DAY.atTime(20, 0), KST).toInstant(), KST);
        }

        @Bean
        FcmSender fcmSender() {
            return new TestFcmSender();
        }
    }

    // 발송 결과를 제어하고 title/body 를 기록하는 대역
    static class TestFcmSender implements FcmSender {
        record SentPush(List<String> tokens, String title, String body) {
        }

        FcmSendResult nextResult = new FcmSendResult(1, List.of());
        List<SentPush> calls = new ArrayList<>();

        @Override
        public FcmSendResult send(List<String> tokens, String title, String body) {
            calls.add(new SentPush(tokens, title, body));
            return nextResult;
        }
    }

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobOperator jobOperator;
    @Autowired
    private Job weeklyReportPushJob;
    @Autowired
    private Clock kstClock;
    @Autowired
    private WeeklyReportRepository weeklyReportRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private NotificationSettingRepository notificationSettingRepository;
    @Autowired
    private UserDeviceTokenRepository userDeviceTokenRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TestFcmSender testFcmSender;

    @AfterEach
    void cleanUp() {
        // 적재 스텝의 중복 판정(type+refId)이 테이블 전체를 보므로 테스트 간 알림이 남으면 서로 간섭함
        notificationRepository.deleteAll();
        notificationSettingRepository.deleteAll();
        userDeviceTokenRepository.deleteAll();
        weeklyReportRepository.deleteAll();
        userRepository.deleteAll();
        testFcmSender.nextResult = new FcmSendResult(1, List.of());
        testFcmSender.calls.clear();
    }

    @Test
    void 회고가_있는_사용자에게_수치를_담은_알림을_적재하고_발송한다() throws Exception {
        User user = signUpWithToken("token-1");
        WeeklyReport report = persistGeneratedReport(user, statsJson(5, 3));

        runJobOnce();

        List<Notification> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(1);
        Notification notification = notifications.getFirst();
        assertThat(notification.getType()).isEqualTo(NotificationType.WEEKLY_REPORT);
        assertThat(notification.getRefId()).isEqualTo(report.getId());
        assertThat(notification.getTitle()).isEqualTo("지난주 루틴 회고가 도착했어요");
        assertThat(notification.getBody()).isEqualTo("지난주 루틴 5회 중 3회를 완료했어요. 이번 주 계획 전에 확인해 보세요.");
        assertThat(notification.getPushStatus()).isEqualTo(PushStatus.SENT);
        assertThat(testFcmSender.calls).hasSize(1);
        assertThat(testFcmSender.calls.getFirst().tokens()).containsExactly("token-1");
        assertThat(testFcmSender.calls.getFirst().body()).contains("5회 중 3회");
    }

    @Test
    void FALLBACK_회고도_동일하게_push한다() throws Exception {
        User user = signUpWithToken("token-fallback");
        WeeklyReport report = weeklyReportRepository.save(WeeklyReport.fallback(user, WEEK_START, WEEK_END,
                statsJson(4, 1), "이번 주 루틴 4회 중 1회를 완료했어요.", EMPTY_SECTIONS_JSON, Instant.now()));

        runJobOnce();

        List<Notification> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.getFirst().getRefId()).isEqualTo(report.getId());
        assertThat(notifications.getFirst().getBody()).contains("4회 중 1회");
        assertThat(notifications.getFirst().getPushStatus()).isEqualTo(PushStatus.SENT);
    }

    @Test
    void 같은_주를_재실행해도_중복_알림을_만들지_않는다() throws Exception {
        User user = signUpWithToken("token-rerun");
        persistGeneratedReport(user, statsJson(5, 3));

        runJobOnce();
        runJobOnce();

        assertThat(notificationRepository.findAll()).hasSize(1);
        assertThat(testFcmSender.calls).hasSize(1);
    }

    @Test
    void 리마인더_설정을_끈_사용자는_push하지_않고_BLOCKED로_종결한다() throws Exception {
        User user = signUpWithToken("token-blocked");
        notificationSettingRepository.save(NotificationSetting.create(user, NotificationSettingType.REMINDER, false));
        persistGeneratedReport(user, statsJson(5, 3));

        runJobOnce();

        List<Notification> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.getFirst().getPushStatus()).isEqualTo(PushStatus.BLOCKED);
        assertThat(testFcmSender.calls).isEmpty();
    }

    @Test
    void 회고가_없는_주면_아무것도_하지_않는다() throws Exception {
        // 다른 주의 회고만 있음 → 대상 주 필터로 제외
        User user = signUpWithToken("token-other-week");
        weeklyReportRepository.save(WeeklyReport.generated(user, WEEK_START.minusDays(7), WEEK_END.minusDays(7),
                "test-model", statsJson(2, 2), "요약", EMPTY_SECTIONS_JSON, Instant.now()));

        runJobOnce();

        assertThat(notificationRepository.findAll()).isEmpty();
        assertThat(testFcmSender.calls).isEmpty();
    }

    @Test
    void 탈퇴한_사용자의_잔여_회고는_push_대상에서_제외한다() throws Exception {
        User withdrawn = signUpWithToken("token-withdrawn");
        persistGeneratedReport(withdrawn, statsJson(3, 3));
        withdrawn.softDelete(Instant.now());
        userRepository.save(withdrawn);

        runJobOnce();

        assertThat(notificationRepository.findAll()).isEmpty();
        assertThat(testFcmSender.calls).isEmpty();
    }

    @Test
    void statsJson이_깨진_회고는_그_사용자만_skip하고_나머지는_발송한다() throws Exception {
        // MySQL JSON 컬럼이라 JSON 자체는 유효해야 저장된다 - 스키마가 어긋난 값으로 역직렬화 실패를 만든다
        User broken = signUpWithToken("token-broken");
        weeklyReportRepository.save(WeeklyReport.generated(broken, WEEK_START, WEEK_END, "test-model",
                "{\"scheduledCount\":\"수치아님\"}", "요약", EMPTY_SECTIONS_JSON, Instant.now()));
        User healthy = signUpWithToken("token-healthy");
        WeeklyReport healthyReport = persistGeneratedReport(healthy, statsJson(6, 4));

        runJobOnce();

        List<Notification> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.getFirst().getRefId()).isEqualTo(healthyReport.getId());
        assertThat(notifications.getFirst().getPushStatus()).isEqualTo(PushStatus.SENT);
    }

    @Test
    void 이전_주에_적재되고_발송_전_중단된_잔존_PENDING은_다음_주_발송에_섞이지_않는다() throws Exception {
        // 지난주: 적재(Step1) 커밋 후 발송(Step2) 전에 중단된 상황을 재현 — PENDING 알림만 남아 있음
        User staleUser = signUpWithToken("token-stale");
        WeeklyReport staleReport = weeklyReportRepository.save(WeeklyReport.generated(staleUser,
                WEEK_START.minusDays(7), WEEK_START.minusDays(1), "test-model", statsJson(7, 3), "요약",
                EMPTY_SECTIONS_JSON, Instant.now()));
        Notification stale = notificationRepository.save(Notification.create(staleUser,
                NotificationType.WEEKLY_REPORT, "지난주 루틴 회고가 도착했어요", "옛 본문", staleReport.getId()));
        // 이번 주 정상 대상
        User user = signUpWithToken("token-current");
        WeeklyReport report = persistGeneratedReport(user, statsJson(7, 5));

        runJobOnce();

        // 이번 주 회고만 발송되고, 잔존분은 PENDING 인 채 미발송으로 만료된다("뒤늦은 지난 주 push 없음")
        assertThat(testFcmSender.calls).hasSize(1);
        assertThat(testFcmSender.calls.getFirst().tokens()).containsExactly("token-current");
        assertThat(notificationRepository.findById(stale.getId()).orElseThrow().getPushStatus())
                .isEqualTo(PushStatus.PENDING);
        List<Notification> currentNotifications = notificationRepository.findAll().stream()
                .filter(notification -> notification.getRefId().equals(report.getId())).toList();
        assertThat(currentNotifications).hasSize(1);
        assertThat(currentNotifications.getFirst().getPushStatus()).isEqualTo(PushStatus.SENT);
    }

    @Test
    void 같은_weekStart_재실행은_JobInstance_유일성으로_막힌다() throws Exception {
        LocalDate otherWeek = WEEK_START.minusDays(14);
        JobParameters params = weekStartParams(otherWeek);

        JobExecution first = jobOperatorTestUtils.startJob(params);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThatThrownBy(() -> jobOperatorTestUtils.startJob(params))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @Test
    void 트리거는_회고_생성이_완료된_뒤에만_직전_주를_적재하고_발송한다() throws Exception {
        // 이 테스트만 전용 주(2025-05-04~10)를 쓴다 — 생성 완료 게이트가 weeklyReportJob 메타데이터를 보므로,
        // 생성 job 통합 테스트가 같은 DB 에 남긴 2025-03-09 인스턴스와 겹치면 1단계(보류)가 성립하지 않는다.
        LocalDate triggerDay = LocalDate.of(2025, 5, 11);
        LocalDate weekStart = LocalDate.of(2025, 5, 4);
        Clock mayClock = Clock.fixed(ZonedDateTime.of(triggerDay.atTime(20, 0), KST).toInstant(), KST);
        User user = signUpWithToken("token-trigger");
        WeeklyReport report = weeklyReportRepository.save(WeeklyReport.generated(user, weekStart,
                weekStart.plusDays(6), "test-model", statsJson(7, 5), "요약", EMPTY_SECTIONS_JSON, Instant.now()));
        WeeklyReportPushTrigger trigger =
                new WeeklyReportPushTrigger(jobOperator, weeklyReportPushJob, jobRepository, mayClock);

        // 발송 시각(일요일 20:00)이 지났어도 그 주 생성 job 이 COMPLETED 가 아니면 보류 —
        // 빈 상태로 주당 1회 JobInstance 를 소모해 뒤늦게 생성된 회고의 push 가 영구 누락되는 것을 막는다
        trigger.runForLatestCompletedWeek();
        assertThat(jobRepository.getLastJobExecution(
                WeeklyReportPushJobConfig.JOB_NAME, weekStartParams(weekStart))).isNull();
        assertThat(notificationRepository.findAll()).isEmpty();

        // 생성 job COMPLETED 기록 뒤 → 발송 (생성 job 자체를 돌리는 대신 메타데이터만 만든다)
        markGenerationCompleted(weekStart);
        trigger.runForLatestCompletedWeek();

        JobExecution execution = jobRepository.getLastJobExecution(
                WeeklyReportPushJobConfig.JOB_NAME, weekStartParams(weekStart));
        assertThat(execution).isNotNull();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<Notification> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.getFirst().getRefId()).isEqualTo(report.getId());
        assertThat(notifications.getFirst().getPushStatus()).isEqualTo(PushStatus.SENT);
    }

    // weeklyReportJob(weekStart) 인스턴스를 COMPLETED 로 남겨 push 트리거의 생성 완료 게이트를 통과시킨다.
    private void markGenerationCompleted(LocalDate weekStart) {
        JobParameters params = new JobParametersBuilder()
                .addString(WeeklyReportJobConfig.WEEK_START_PARAM, weekStart.toString())
                .toJobParameters();
        org.springframework.batch.core.job.JobInstance instance =
                jobRepository.createJobInstance(WeeklyReportJobConfig.JOB_NAME, params);
        JobExecution execution = jobRepository.createJobExecution(instance, params,
                new org.springframework.batch.infrastructure.item.ExecutionContext());
        execution.setStatus(BatchStatus.COMPLETED);
        execution.setEndTime(java.time.LocalDateTime.now());
        jobRepository.update(execution);
    }

    // 테스트마다 run 파라미터를 달리해 같은 weekStart 로도 job 을 실제 실행시킨다(인스턴스 유일성 우회).
    private void runJobOnce() throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addString(WeeklyReportPushJobConfig.WEEK_START_PARAM, WEEK_START.toString())
                .addString("run", UUID.randomUUID().toString())
                .toJobParameters();
        JobExecution execution = jobOperatorTestUtils.startJob(params);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    private static JobParameters weekStartParams(LocalDate weekStart) {
        return new JobParametersBuilder()
                .addString(WeeklyReportPushJobConfig.WEEK_START_PARAM, weekStart.toString())
                .toJobParameters();
    }

    private static String statsJson(int scheduled, int completed) {
        return """
                {"scheduledCount":%d,"completedCount":%d,"failedCount":%d,"completionRate":0.5,
                 "byWeekday":[],"byRoutine":[],"streak":{"currentCount":0,"longestCount":0}}"""
                .formatted(scheduled, completed, scheduled - completed);
    }

    private User signUpWithToken(String token) {
        User user = userRepository.save(User.signUp());
        userDeviceTokenRepository.save(UserDeviceToken.register(user, token, DevicePlatform.ANDROID, Instant.now()));
        return user;
    }

    private WeeklyReport persistGeneratedReport(User user, String statsJson) {
        return weeklyReportRepository.save(WeeklyReport.generated(user, WEEK_START, WEEK_END, "test-model",
                statsJson, "요약", EMPTY_SECTIONS_JSON, Instant.now()));
    }
}
