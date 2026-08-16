package com.triples.rougether.batch.weeklyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.batch.config.BatchJdbcConfig;
import com.triples.rougether.batch.dayend.DayEndCompletionChecker;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.report.entity.WeeklyReport;
import com.triples.rougether.domain.report.entity.WeeklyReportStatus;
import com.triples.rougether.domain.report.repository.WeeklyReportRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.infra.llm.LlmAuthException;
import com.triples.rougether.infra.llm.LlmChatRequest;
import com.triples.rougether.infra.llm.LlmClient;
import com.triples.rougether.infra.llm.LlmException;
import com.triples.rougether.infra.llm.LlmProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.infrastructure.item.ExecutionContext;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// spring.batch.job.enabled=false: 자동설정의 JobLauncherApplicationRunner 가 기동 시 빈 파라미터로 job 을 돌리면
// weekStart 검증기가 컨텍스트 로딩을 실패시키므로(운영 yml 과 동일하게) 끈다.
@SpringBootTest(classes = WeeklyReportJobIntegrationTest.TestConfig.class,
        properties = "spring.batch.job.enabled=false")
@SpringBatchTest
class WeeklyReportJobIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 트리거 기준 "지금": 2025-03-16(일) 00:30 KST → 대상 주 = 2025-03-09(일) ~ 2025-03-15(토).
    // Spring Batch 메타데이터 DB(Testcontainers)는 같은 JVM 의 다른 배치 테스트와 공유되므로, 실제 날짜 기준으로 도는
    // day-end 테스트가 남긴 routineDayEndJob 인스턴스와 겹치지 않게 과거의 고정 날짜를 쓴다.
    private static final LocalDate TRIGGER_DAY = LocalDate.of(2025, 3, 16);
    private static final LocalDate WEEK_START = LocalDate.of(2025, 3, 9);
    private static final LocalDate WEEK_END = LocalDate.of(2025, 3, 15);
    private static final String VALID_JSON = """
            {"summary":"이번 주 3회 중 2회 완료했어요.","highlights":["일요일 달리기"],
             "failurePatterns":["화요일 실패"],"suggestions":["알람 켜기"]}""";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.triples.rougether.domain")
    @EnableJpaRepositories("com.triples.rougether.domain")
    @EnableJpaAuditing
    @Import({BatchJdbcConfig.class, WeeklyReportJobConfig.class, WeeklyStatsAggregator.class,
            WeeklyReportPromptBuilder.class, WeeklyReportParser.class, WeeklyReportTrigger.class,
            DayEndCompletionChecker.class})
    static class TestConfig {

        @Bean
        Clock kstClock() {
            return Clock.fixed(ZonedDateTime.of(TRIGGER_DAY.atTime(0, 30), KST).toInstant(), KST);
        }

        @Bean
        LlmProperties llmProperties() {
            return new LlmProperties("http://llm.test/v1", "test-model", "", Duration.ofSeconds(5), null, 800,
                    true, "low", 0, Duration.ofMillis(1));
        }

        @Bean
        ScriptedLlmClient llmClient() {
            return new ScriptedLlmClient();
        }
    }

    // 응답 시나리오를 큐로 제어하는 LLM 대역. 큐가 비면 항상 유효 JSON.
    static class ScriptedLlmClient implements LlmClient {
        final Deque<Supplier<String>> script = new ArrayDeque<>();
        int calls;
        LlmChatRequest lastRequest;

        @Override
        public String complete(LlmChatRequest request) {
            calls++;
            lastRequest = request;
            Supplier<String> next = script.poll();
            return next == null ? VALID_JSON : next.get();
        }

        void reset() {
            script.clear();
            calls = 0;
            lastRequest = null;
        }
    }

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private WeeklyReportTrigger weeklyReportTrigger;
    @Autowired
    private WeeklyReportRepository weeklyReportRepository;
    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private RoutineLogRepository routineLogRepository;
    @Autowired
    private StreakRepository streakRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ScriptedLlmClient llmClient;

    @BeforeEach
    void resetLlm() {
        llmClient.reset();
    }

    @AfterEach
    void cleanUp() {
        weeklyReportRepository.deleteAll();
        streakRepository.deleteAll();
        routineLogRepository.deleteAll();
        routineRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void 대상_사용자에게_GENERATED_회고를_저장한다() throws Exception {
        User user = signUp("진형", "매일 조금씩");
        Routine run = persistRoutine(user, "달리기");
        completed(run, WEEK_START);
        completed(run, WEEK_START.plusDays(1));
        failed(run, WEEK_START.plusDays(2));
        Streak streak = Streak.start(user, WEEK_START);
        streak.applySuccess(WEEK_START.plusDays(1));
        streakRepository.save(streak);

        runJobOnce();

        List<WeeklyReport> reports = weeklyReportRepository.findByUserIdOrderByWeekStartDateDesc(user.getId());
        assertThat(reports).hasSize(1);
        WeeklyReport report = reports.getFirst();
        assertThat(report.getStatus()).isEqualTo(WeeklyReportStatus.GENERATED);
        assertThat(report.getWeekStartDate()).isEqualTo(WEEK_START);
        assertThat(report.getWeekEndDate()).isEqualTo(WEEK_END);
        assertThat(report.getModel()).isEqualTo("test-model");
        assertThat(report.getSummary()).isEqualTo("이번 주 3회 중 2회 완료했어요.");
        JsonNode sections = json(report.getSectionsJson());
        assertThat(sections.path("highlights").get(0).asString()).isEqualTo("일요일 달리기");
        assertThat(sections.path("failurePatterns").get(0).asString()).isEqualTo("화요일 실패");
        assertThat(sections.path("suggestions").get(0).asString()).isEqualTo("알람 켜기");
        JsonNode stats = json(report.getStatsJson());
        assertThat(stats.path("scheduledCount").asInt()).isEqualTo(3);
        assertThat(stats.path("completedCount").asInt()).isEqualTo(2);
        assertThat(stats.path("failedCount").asInt()).isEqualTo(1);
        assertThat(stats.path("completionRate").asDouble()).isEqualTo(0.67);
        assertThat(stats.path("byRoutine").get(0).path("title").asString()).isEqualTo("달리기");
        assertThat(stats.path("byWeekday")).hasSize(7);
        assertThat(stats.path("streak").path("currentCount").asInt()).isEqualTo(2);
        assertThat(report.getGeneratedAt()).isEqualTo(ZonedDateTime.of(TRIGGER_DAY.atTime(0, 30), KST).toInstant());
        assertThat(llmClient.calls).isEqualTo(1);
        assertThat(llmClient.lastRequest.userPrompt()).contains("닉네임: 「진형」").contains("자기소개: 「매일 조금씩」");
    }

    @Test
    void 그_주에_로그가_없는_사용자는_회고를_만들지_않는다() throws Exception {
        User noLogs = signUp("무로그", null);
        persistRoutine(noLogs, "루틴만 있음");
        User outsideWeek = signUp("주밖", null);
        Routine outside = persistRoutine(outsideWeek, "지난달 루틴");
        completed(outside, WEEK_START.minusDays(1));
        completed(outside, WEEK_END.plusDays(1));

        runJobOnce();

        assertThat(weeklyReportRepository.count()).isZero();
        assertThat(llmClient.calls).isZero();
    }

    @Test
    void 이미_회고가_있는_주는_건너뛰고_LLM을_호출하지_않는다() throws Exception {
        User user = signUp("기존", null);
        Routine run = persistRoutine(user, "달리기");
        completed(run, WEEK_START);
        weeklyReportRepository.save(WeeklyReport.fallback(user, WEEK_START, WEEK_END, "{}", "기존 회고",
                "{\"highlights\":[],\"failurePatterns\":[],\"suggestions\":[]}", Instant.now()));

        runJobOnce();

        List<WeeklyReport> reports = weeklyReportRepository.findByUserIdOrderByWeekStartDateDesc(user.getId());
        assertThat(reports).hasSize(1);
        assertThat(reports.getFirst().getSummary()).isEqualTo("기존 회고");
        assertThat(llmClient.calls).isZero();
    }

    @Test
    void LLM_호출이_실패하면_FALLBACK으로_통계만_저장한다() throws Exception {
        User user = signUp("폴백", null);
        Routine run = persistRoutine(user, "달리기");
        completed(run, WEEK_START);
        completed(run, WEEK_START.plusDays(3));
        failed(run, WEEK_START.plusDays(4));
        llmClient.script.add(() -> {
            throw new LlmException("503", true);
        });

        runJobOnce();

        WeeklyReport report = weeklyReportRepository.findByUserIdOrderByWeekStartDateDesc(user.getId()).getFirst();
        assertThat(report.getStatus()).isEqualTo(WeeklyReportStatus.FALLBACK);
        assertThat(report.getModel()).isNull();
        assertThat(report.getSummary()).isEqualTo("이번 주 루틴 3회 중 2회를 완료했어요.");
        JsonNode sections = json(report.getSectionsJson());
        assertThat(sections.path("highlights")).isEmpty();
        assertThat(sections.path("failurePatterns")).isEmpty();
        assertThat(sections.path("suggestions")).isEmpty();
        assertThat(json(report.getStatsJson()).path("scheduledCount").asInt()).isEqualTo(3);
        assertThat(llmClient.calls).isEqualTo(1);
    }

    @Test
    void 파싱_실패는_1회_재요청하고_성공하면_GENERATED다() throws Exception {
        User user = signUp("재요청", null);
        Routine run = persistRoutine(user, "달리기");
        completed(run, WEEK_START);
        llmClient.script.add(() -> "회고를 쓸 수 없어요");

        runJobOnce();

        WeeklyReport report = weeklyReportRepository.findByUserIdOrderByWeekStartDateDesc(user.getId()).getFirst();
        assertThat(report.getStatus()).isEqualTo(WeeklyReportStatus.GENERATED);
        assertThat(llmClient.calls).isEqualTo(2);
    }

    @Test
    void 파싱이_두_번_다_실패하면_FALLBACK이다() throws Exception {
        User user = signUp("두번실패", null);
        Routine run = persistRoutine(user, "달리기");
        completed(run, WEEK_START);
        llmClient.script.add(() -> "{\"highlights\":[]}");
        llmClient.script.add(() -> "not json");

        runJobOnce();

        WeeklyReport report = weeklyReportRepository.findByUserIdOrderByWeekStartDateDesc(user.getId()).getFirst();
        assertThat(report.getStatus()).isEqualTo(WeeklyReportStatus.FALLBACK);
        assertThat(llmClient.calls).isEqualTo(2);
    }

    @Test
    void 삭제된_루틴의_로그와_탈퇴_회원은_집계에서_제외한다() throws Exception {
        // 삭제 루틴 로그만 있는 사용자 → 대상 아님
        User onlyDeleted = signUp("삭제만", null);
        Routine deleted = persistRoutine(onlyDeleted, "삭제된 루틴");
        completed(deleted, WEEK_START);
        deleted.softDelete(Instant.now());
        routineRepository.save(deleted);
        // 탈퇴 회원 → 대상 아님
        User withdrawn = signUp("탈퇴", null);
        Routine withdrawnRun = persistRoutine(withdrawn, "탈퇴자 루틴");
        completed(withdrawnRun, WEEK_START);
        withdrawn.softDelete(Instant.now());
        userRepository.save(withdrawn);
        // 살아있는 루틴 1건 + 삭제 루틴 1건 → 살아있는 것만 집계
        User mixed = signUp("혼합", null);
        Routine live = persistRoutine(mixed, "살아있는 루틴");
        completed(live, WEEK_START);
        Routine mixedDeleted = persistRoutine(mixed, "지운 루틴");
        failed(mixedDeleted, WEEK_START.plusDays(1));
        mixedDeleted.softDelete(Instant.now());
        routineRepository.save(mixedDeleted);

        runJobOnce();

        assertThat(weeklyReportRepository.findByUserIdOrderByWeekStartDateDesc(onlyDeleted.getId())).isEmpty();
        assertThat(weeklyReportRepository.findByUserIdOrderByWeekStartDateDesc(withdrawn.getId())).isEmpty();
        List<WeeklyReport> mixedReports = weeklyReportRepository.findByUserIdOrderByWeekStartDateDesc(mixed.getId());
        assertThat(mixedReports).hasSize(1);
        JsonNode stats = json(mixedReports.getFirst().getStatsJson());
        assertThat(stats.path("scheduledCount").asInt()).isEqualTo(1);
        assertThat(stats.path("failedCount").asInt()).isZero();
        assertThat(stats.path("byRoutine")).hasSize(1);
        assertThat(stats.path("byRoutine").get(0).path("title").asString()).isEqualTo("살아있는 루틴");
    }

    @Test
    void 한_사용자의_예기치_않은_예외는_그_사용자만_skip하고_job은_COMPLETED다() throws Exception {
        // user id 오름차순으로 처리되므로 먼저 만든 사용자가 첫 LLM 호출을 받는다
        User broken = signUp("예외", null);
        completed(persistRoutine(broken, "달리기"), WEEK_START);
        User healthy = signUp("정상", null);
        completed(persistRoutine(healthy, "독서"), WEEK_START.plusDays(1));
        // LlmException 이 아닌 예외는 processor 가 FALLBACK 으로 흡수하지 않고 skip 정책으로 넘어간다
        llmClient.script.add(() -> {
            throw new IllegalStateException("집계 중 예기치 않은 오류");
        });

        runJobOnce();

        assertThat(weeklyReportRepository.findByUserIdOrderByWeekStartDateDesc(broken.getId())).isEmpty();
        List<WeeklyReport> healthyReports = weeklyReportRepository.findByUserIdOrderByWeekStartDateDesc(healthy.getId());
        assertThat(healthyReports).hasSize(1);
        assertThat(healthyReports.getFirst().getStatus()).isEqualTo(WeeklyReportStatus.GENERATED);
        assertThat(llmClient.calls).isEqualTo(2);
    }

    @Test
    void reader_페이지_크기를_넘는_대상도_빠짐없이_한_번씩_처리한다() throws Exception {
        int count = 120; // reader PAGE_SIZE(100) 초과
        for (int i = 0; i < count; i++) {
            User user = signUp("대량" + i, null);
            completed(persistRoutine(user, "루틴" + i), WEEK_START.plusDays(i % 7));
        }

        runJobOnce();

        assertThat(weeklyReportRepository.count()).isEqualTo(count);
        assertThat(llmClient.calls).isEqualTo(count);
    }

    @Test
    void LLM_인증_실패는_FALLBACK_대신_job을_FAILED로_끝내고_재시작하면_이어서_처리한다() throws Exception {
        LocalDate week = WEEK_START.minusDays(14);
        User first = signUp("먼저", null);
        completed(persistRoutine(first, "달리기"), week);
        User second = signUp("나중", null);
        completed(persistRoutine(second, "독서"), week.plusDays(1));
        // 첫 사용자는 정상, 두 번째 사용자에서 키 오류
        llmClient.script.add(() -> VALID_JSON);
        llmClient.script.add(() -> {
            throw new LlmAuthException("401", new RuntimeException("unauthorized"));
        });
        JobParameters params = weekStartParams(week);

        JobExecution failed = jobOperatorTestUtils.startJob(params);

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(weeklyReportRepository.findByUserIdOrderByWeekStartDateDesc(first.getId())).hasSize(1);
        assertThat(weeklyReportRepository.findByUserIdOrderByWeekStartDateDesc(second.getId())).isEmpty();

        // 키를 고친 뒤 같은 weekStart 로 재시작 → 이미 있는 사용자는 건너뛰고 나머지만 생성
        llmClient.reset();
        JobExecution restarted = jobOperatorTestUtils.startJob(params);

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(weeklyReportRepository.findByUserIdOrderByWeekStartDateDesc(first.getId())).hasSize(1);
        assertThat(weeklyReportRepository.findByUserIdOrderByWeekStartDateDesc(second.getId())).hasSize(1);
        assertThat(llmClient.calls).isEqualTo(1);
    }

    @Test
    void 같은_weekStart_재실행은_JobInstance_유일성으로_막힌다() throws Exception {
        LocalDate otherWeek = WEEK_START.minusDays(7);
        JobParameters params = weekStartParams(otherWeek);

        JobExecution first = jobOperatorTestUtils.startJob(params);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThatThrownBy(() -> jobOperatorTestUtils.startJob(params))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @Test
    void 트리거는_토요일_day_end가_완료된_뒤에만_job을_시작한다() throws Exception {
        User user = signUp("트리거", null);
        Routine run = persistRoutine(user, "달리기");
        completed(run, WEEK_START.plusDays(2));

        // 토요일(WEEK_END) day-end 미완료 → 보류
        weeklyReportTrigger.runForLatestCompletedWeek();
        assertThat(jobRepository.getLastJobExecution(WeeklyReportJobConfig.JOB_NAME, weekStartParams(WEEK_START)))
                .isNull();
        assertThat(weeklyReportRepository.count()).isZero();

        // 토요일 day-end 가 COMPLETED 로 기록된 뒤 → 실행 (day-end job 자체를 돌리는 대신 메타데이터만 만든다)
        markDayEndCompleted(WEEK_END);

        weeklyReportTrigger.runForLatestCompletedWeek();
        JobExecution execution = jobRepository.getLastJobExecution(
                WeeklyReportJobConfig.JOB_NAME, weekStartParams(WEEK_START));
        assertThat(execution).isNotNull();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(weeklyReportRepository.findByUserIdOrderByWeekStartDateDesc(user.getId())).hasSize(1);
    }

    // routineDayEndJob(targetDate) 인스턴스를 COMPLETED 상태로 남겨 DayEndCompletionChecker 가 참을 돌려주게 한다.
    private void markDayEndCompleted(LocalDate targetDate) {
        JobParameters params = new JobParametersBuilder()
                .addString("targetDate", targetDate.toString()).toJobParameters();
        JobInstance instance = jobRepository.createJobInstance("routineDayEndJob", params);
        JobExecution execution = jobRepository.createJobExecution(instance, params, new ExecutionContext());
        execution.setStatus(BatchStatus.COMPLETED);
        execution.setEndTime(java.time.LocalDateTime.now());
        jobRepository.update(execution);
    }

    // 테스트마다 run 파라미터를 달리해 같은 weekStart 로도 job 을 실제 실행시킨다(인스턴스 유일성 우회).
    private void runJobOnce() throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addString(WeeklyReportJobConfig.WEEK_START_PARAM, WEEK_START.toString())
                .addString("run", UUID.randomUUID().toString())
                .toJobParameters();
        JobExecution execution = jobOperatorTestUtils.startJob(params);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    private static JsonNode json(String raw) {
        return JsonMapper.builder().build().readTree(raw);
    }

    private static JobParameters weekStartParams(LocalDate weekStart) {
        return new JobParametersBuilder()
                .addString(WeeklyReportJobConfig.WEEK_START_PARAM, weekStart.toString())
                .toJobParameters();
    }

    private User signUp(String nickname, String bio) {
        User user = User.signUp();
        user.changeNickname(nickname);
        if (bio != null) {
            user.changeBio(bio);
        }
        return userRepository.save(user);
    }

    private Routine persistRoutine(User user, String title) {
        Routine routine = routineRepository.save(Routine.create(user, null, title, AuthType.CHECK,
                "DAILY", null, null, null, null));
        routine.assignOriginToSelf();
        return routineRepository.save(routine);
    }

    private void completed(Routine routine, LocalDate date) {
        routineLogRepository.save(RoutineLog.complete(routine, date, Instant.now(), CurrencyType.COIN, 10));
    }

    private void failed(Routine routine, LocalDate date) {
        routineLogRepository.save(RoutineLog.fail(routine, date));
    }
}
