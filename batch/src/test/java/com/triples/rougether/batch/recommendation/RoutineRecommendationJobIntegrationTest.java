package com.triples.rougether.batch.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.batch.config.BatchJdbcConfig;
import com.triples.rougether.batch.dayend.DayEndCompletionChecker;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.recommendation.entity.RecommendationSource;
import com.triples.rougether.domain.recommendation.entity.RecommendationStatus;
import com.triples.rougether.domain.recommendation.entity.RecommendationType;
import com.triples.rougether.domain.recommendation.entity.RoutineRecommendation;
import com.triples.rougether.domain.recommendation.repository.RoutineRecommendationRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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

// spring.batch.job.enabled=false: 자동설정 runner 가 빈 파라미터로 job 을 돌리면 weekStart 검증기에 걸리므로 끈다.
@SpringBootTest(classes = RoutineRecommendationJobIntegrationTest.TestConfig.class,
        properties = "spring.batch.job.enabled=false")
@SpringBatchTest
class RoutineRecommendationJobIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 트리거 기준 "지금": 2025-06-15(일) 01:00 KST → 대상 주 = 2025-06-08(일)~06-14(토), 근거 창 = 05-25~06-14.
    // Batch 메타데이터 DB(Testcontainers)는 같은 JVM 의 다른 배치 테스트와 공유되므로 다른 테스트가 안 쓰는 날짜를 쓴다.
    private static final LocalDate TRIGGER_DAY = LocalDate.of(2025, 6, 15);
    private static final LocalDate WEEK_START = LocalDate.of(2025, 6, 8);
    private static final LocalDate WEEK_END = LocalDate.of(2025, 6, 14);
    private static final Instant NOW = ZonedDateTime.of(TRIGGER_DAY.atTime(1, 0), KST).toInstant();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.triples.rougether.domain")
    @EnableJpaRepositories("com.triples.rougether.domain")
    @EnableJpaAuditing
    @Import({BatchJdbcConfig.class, RoutineRecommendationJobConfig.class, RecommendationRuleEvaluator.class,
            RoutineRecommendationTrigger.class, DayEndCompletionChecker.class})
    static class TestConfig {

        @Bean
        Clock kstClock() {
            return Clock.fixed(NOW, KST);
        }
    }

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private RoutineRecommendationTrigger trigger;
    @Autowired
    private RoutineRecommendationRepository recommendationRepository;
    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private RoutineLogRepository routineLogRepository;
    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void cleanUp() {
        recommendationRepository.deleteAll();
        routineLogRepository.deleteAll();
        routineRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void 실패_요일_패턴이_있는_사용자에게_ACTIVE_추천을_저장한다() throws Exception {
        User user = signUp("패턴");
        Routine routine = weeklyRoutine(user, "아침 러닝", "MON", "WED", "FRI");
        failEveryWeek(routine, DayOfWeek.WEDNESDAY);
        completeEveryWeek(routine, DayOfWeek.MONDAY);
        completeEveryWeek(routine, DayOfWeek.FRIDAY);

        runJobOnce();

        List<RoutineRecommendation> recommendations = recommendationRepository.findAll();
        assertThat(recommendations).hasSize(1);
        RoutineRecommendation recommendation = recommendations.getFirst();
        assertThat(recommendation.getUser().getId()).isEqualTo(user.getId());
        assertThat(recommendation.getOriginRoutineId()).isEqualTo(routine.getOriginRoutineId());
        assertThat(recommendation.getRoutineId()).isEqualTo(routine.getId());
        assertThat(recommendation.getRecType()).isEqualTo(RecommendationType.ADJUST_DAYS);
        assertThat(recommendation.getSource()).isEqualTo(RecommendationSource.RULE);
        assertThat(recommendation.getStatus()).isEqualTo(RecommendationStatus.ACTIVE);
        assertThat(recommendation.getProposalJson()).contains("\"WEEKLY\"").contains("MON").contains("FRI")
                .doesNotContain("WED");
        assertThat(recommendation.getMessage()).contains("아침 러닝").contains("수요일");
        assertThat(recommendation.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(recommendation.getActedAt()).isNull();
        assertThat(recommendation.getAppliedRoutineId()).isNull();
    }

    @Test
    void 패턴이_없거나_창_밖_로그뿐인_사용자에게는_추천을_만들지_않는다() throws Exception {
        // 로그는 있지만 실패 패턴 없음
        User healthy = signUp("건강");
        Routine ok = weeklyRoutine(healthy, "독서", "MON", "WED");
        completeEveryWeek(ok, DayOfWeek.MONDAY);
        completeEveryWeek(ok, DayOfWeek.WEDNESDAY);
        // 근거 창 밖(4주 전) 로그만 있음
        User stale = signUp("옛기록");
        Routine old = weeklyRoutine(stale, "옛 루틴", "MON", "WED");
        failed(old, WEEK_START.minusWeeks(4).plusDays(3));

        runJobOnce();

        assertThat(recommendationRepository.count()).isZero();
    }

    @Test
    void 사용자당_활성_추천은_3건까지만_만든다() throws Exception {
        User user = signUp("다작");
        for (int i = 0; i < 4; i++) {
            Routine routine = weeklyRoutine(user, "루틴" + i, "MON", "WED");
            failEveryWeek(routine, DayOfWeek.WEDNESDAY);
            completeEveryWeek(routine, DayOfWeek.MONDAY);
        }

        runJobOnce();

        assertThat(recommendationRepository.count()).isEqualTo(3);
    }

    @Test
    void 기존_활성_추천이_상한을_채우고_있으면_새_추천을_만들지_않는다() throws Exception {
        User user = signUp("상한");
        // 상한을 채우는 기존 활성 추천 3건(다른 계보, 미만료)
        for (int i = 0; i < 3; i++) {
            Routine occupied = weeklyRoutine(user, "기존" + i, "MON", "WED");
            recommendationRepository.save(RoutineRecommendation.rule(user, occupied.getOriginRoutineId(),
                    occupied.getId(), RecommendationType.ADJUST_DAYS,
                    "{\"repeatType\":\"WEEKLY\",\"daysOfWeek\":[\"MON\"]}", "기존 추천", NOW.plus(Duration.ofDays(3))));
        }
        // 새로 패턴이 생긴 루틴(쿨다운과 무관한 별개 계보)
        Routine target = weeklyRoutine(user, "새 패턴", "TUE", "THU");
        failEveryWeek(target, DayOfWeek.THURSDAY);
        completeEveryWeek(target, DayOfWeek.TUESDAY);

        runJobOnce();

        assertThat(recommendationRepository.count()).isEqualTo(3);
        assertThat(recommendationRepository.findOriginRoutineIdsCreatedAfter(user.getId(),
                NOW.minus(Duration.ofDays(1))))
                .doesNotContain(target.getOriginRoutineId());
    }

    @Test
    void 같은_계보에_14일_내_추천이_있으면_상태와_무관하게_다시_만들지_않는다() throws Exception {
        User user = signUp("쿨다운");
        Routine routine = weeklyRoutine(user, "아침 러닝", "MON", "WED");
        failEveryWeek(routine, DayOfWeek.WEDNESDAY);
        completeEveryWeek(routine, DayOfWeek.MONDAY);
        // 같은 계보의 무시된(종결) 추천이 이미 있음 — 쿨다운은 상태 무관
        RoutineRecommendation dismissed = RoutineRecommendation.rule(user, routine.getOriginRoutineId(),
                routine.getId(), RecommendationType.ADJUST_DAYS,
                "{\"repeatType\":\"WEEKLY\",\"daysOfWeek\":[\"MON\"]}", "무시한 추천", NOW.plus(Duration.ofDays(3)));
        dismissed.dismiss(NOW);
        recommendationRepository.save(dismissed);

        runJobOnce();

        assertThat(recommendationRepository.count()).isEqualTo(1);
    }

    @Test
    void 같은_weekStart_재실행은_JobInstance_유일성으로_막힌다() throws Exception {
        JobParameters params = weekStartParams(WEEK_START.minusWeeks(1));

        JobExecution first = jobOperatorTestUtils.startJob(params);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThatThrownBy(() -> jobOperatorTestUtils.startJob(params))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @Test
    void 트리거는_토요일_day_end가_완료된_뒤에만_job을_시작한다() throws Exception {
        User user = signUp("트리거");
        Routine routine = weeklyRoutine(user, "아침 러닝", "MON", "WED");
        failEveryWeek(routine, DayOfWeek.WEDNESDAY);
        completeEveryWeek(routine, DayOfWeek.MONDAY);

        // 토요일(WEEK_END) day-end 미완료 → 보류
        trigger.runForLatestCompletedWeek();
        assertThat(jobRepository.getLastJobExecution(
                RoutineRecommendationJobConfig.JOB_NAME, weekStartParams(WEEK_START))).isNull();
        assertThat(recommendationRepository.count()).isZero();

        // 토요일 day-end COMPLETED 기록 뒤 → 실행
        markDayEndCompleted(WEEK_END);
        trigger.runForLatestCompletedWeek();

        JobExecution execution = jobRepository.getLastJobExecution(
                RoutineRecommendationJobConfig.JOB_NAME, weekStartParams(WEEK_START));
        assertThat(execution).isNotNull();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(recommendationRepository.count()).isEqualTo(1);
    }

    // ---------- fixtures ----------

    // routineDayEndJob(targetDate) 인스턴스를 COMPLETED 로 남겨 DayEndCompletionChecker 가 참을 돌려주게 한다.
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
                .addString(RoutineRecommendationJobConfig.WEEK_START_PARAM, WEEK_START.toString())
                .addString("run", UUID.randomUUID().toString())
                .toJobParameters();
        JobExecution execution = jobOperatorTestUtils.startJob(params);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    private static JobParameters weekStartParams(LocalDate weekStart) {
        return new JobParametersBuilder()
                .addString(RoutineRecommendationJobConfig.WEEK_START_PARAM, weekStart.toString())
                .toJobParameters();
    }

    private User signUp(String nickname) {
        User user = User.signUp();
        user.changeNickname(nickname);
        return userRepository.save(user);
    }

    private Routine weeklyRoutine(User user, String title, String... dayTokens) {
        String repeatDays = "{\"daysOfWeek\":[\"" + String.join("\",\"", dayTokens) + "\"]}";
        Routine routine = routineRepository.save(Routine.create(user, null, title, AuthType.CHECK,
                "WEEKLY", repeatDays, null, null, null));
        routine.assignOriginToSelf();
        return routineRepository.save(routine);
    }

    // 근거 창 3주의 해당 요일에 매주 FAILED log
    private void failEveryWeek(Routine routine, DayOfWeek day) {
        for (int week = 0; week < 3; week++) {
            failed(routine, dateOf(week, day));
        }
    }

    private void completeEveryWeek(Routine routine, DayOfWeek day) {
        for (int week = 0; week < 3; week++) {
            routineLogRepository.save(RoutineLog.complete(routine, dateOf(week, day),
                    Instant.now(), CurrencyType.COIN, 0));
        }
    }

    private void failed(Routine routine, LocalDate date) {
        routineLogRepository.save(RoutineLog.fail(routine, date));
    }

    private static LocalDate dateOf(int week, DayOfWeek day) {
        LocalDate weekStart = WEEK_START.minusWeeks(2L - week);
        int offset = day == DayOfWeek.SUNDAY ? 0 : day.getValue();
        return weekStart.plusDays(offset);
    }
}
