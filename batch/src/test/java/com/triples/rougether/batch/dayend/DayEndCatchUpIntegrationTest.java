package com.triples.rougether.batch.dayend;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.batch.config.BatchJdbcConfig;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = DayEndCatchUpIntegrationTest.TestConfig.class)
class DayEndCatchUpIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.triples.rougether.domain")
    @EnableJpaRepositories("com.triples.rougether.domain")
    @EnableJpaAuditing
    @Import({BatchJdbcConfig.class, RoutineDayEndJobConfig.class,
            DayEndCatchUpPlanner.class, RoutineDayEndTrigger.class})
    static class TestConfig {

        @Bean
        Clock kstClock() {
            return Clock.system(ZoneId.of("Asia/Seoul"));
        }
    }

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate YESTERDAY = LocalDate.now(KST).minusDays(1);

    @Autowired
    private RoutineDayEndTrigger trigger;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private RoutineLogRepository routineLogRepository;

    // 컨텍스트 기동 시 ApplicationReady catch-up이 남긴 기록까지 지워 각 테스트가 깨끗한 메타데이터에서 시작.
    // 종료 후에도 지워야 같은 컨테이너를 재사용하는 다른 배치 테스트 클래스와 targetDate instance가 충돌하지 않음
    @BeforeEach
    @AfterEach
    void resetBatchMetadata() {
        jdbcTemplate.update("delete from BATCH_STEP_EXECUTION_CONTEXT");
        jdbcTemplate.update("delete from BATCH_STEP_EXECUTION");
        jdbcTemplate.update("delete from BATCH_JOB_EXECUTION_CONTEXT");
        jdbcTemplate.update("delete from BATCH_JOB_EXECUTION_PARAMS");
        jdbcTemplate.update("delete from BATCH_JOB_EXECUTION");
        jdbcTemplate.update("delete from BATCH_JOB_INSTANCE");
    }

    @AfterEach
    void cleanUpDomainRows() {
        routineLogRepository.deleteAll();
        routineRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void 실행_기록이_없으면_어제만_실행한다() {
        trigger.triggerDayEnd();

        assertThat(targetDatesInInstanceOrder()).containsExactly(YESTERDAY);
        assertThat(lastStatusOf(YESTERDAY)).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void gap이_없으면_어제_하루만_추가_실행한다() {
        seedExecution(YESTERDAY.minusDays(1), BatchStatus.COMPLETED);

        trigger.triggerDayEnd();

        assertThat(targetDatesInInstanceOrder())
                .containsExactly(YESTERDAY.minusDays(1), YESTERDAY);
        assertThat(lastStatusOf(YESTERDAY)).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void 삼일_gap이면_세_날짜를_오래된_순으로_실행한다() {
        seedExecution(YESTERDAY.minusDays(3), BatchStatus.COMPLETED);

        trigger.triggerDayEnd();

        // instance 생성 순서(id 오름차순)가 곧 실행 순서 - 오래된 날짜부터
        assertThat(targetDatesInInstanceOrder()).containsExactly(
                YESTERDAY.minusDays(3), YESTERDAY.minusDays(2), YESTERDAY.minusDays(1), YESTERDAY);
        for (LocalDate date : List.of(YESTERDAY.minusDays(2), YESTERDAY.minusDays(1), YESTERDAY)) {
            assertThat(lastStatusOf(date)).isEqualTo(BatchStatus.COMPLETED);
        }
    }

    @Test
    void 밀린_날짜가_없으면_잡을_실행하지_않는다() {
        // 매시 정각 재검사의 평시 상태 - 어제까지 처리됐으면 planner가 빈 목록을 반환해 no-op
        seedExecution(YESTERDAY, BatchStatus.COMPLETED);

        trigger.triggerDayEnd();

        assertThat(countExecutions()).isEqualTo(1);
        assertThat(targetDatesInInstanceOrder()).containsExactly(YESTERDAY);
    }

    @Test
    void 재실행해도_중복_실행되지_않는다() {
        seedExecution(YESTERDAY.minusDays(1), BatchStatus.COMPLETED);

        trigger.triggerDayEnd();
        long executionsAfterFirst = countExecutions();
        trigger.triggerDayEnd();

        assertThat(countExecutions()).isEqualTo(executionsAfterFirst);
        assertThat(targetDatesInInstanceOrder())
                .containsExactly(YESTERDAY.minusDays(1), YESTERDAY);
    }

    @Test
    void 실패한_날짜는_다음_트리거에서_재실행되고_FAILED_로그가_중복없이_생긴다() {
        LocalDate failedDate = YESTERDAY.minusDays(1);
        seedExecution(YESTERDAY.minusDays(2), BatchStatus.COMPLETED);
        seedExecution(failedDate, BatchStatus.FAILED);
        Long routineId = persistDailyExistingSince(failedDate.minusDays(9));

        trigger.triggerDayEnd();

        // FAILED는 COMPLETED가 아니므로 gap에 다시 포함 - 같은 instance에 재실행 execution이 추가됨
        JobInstance failedInstance = jobRepository.getJobInstance(
                RoutineDayEndJobConfig.JOB_NAME, targetDateParams(failedDate));
        assertThat(jobRepository.getJobExecutions(failedInstance)).hasSize(2);
        assertThat(lastStatusOf(failedDate)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(lastStatusOf(YESTERDAY)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(targetDatesInInstanceOrder()).containsExactly(
                YESTERDAY.minusDays(2), failedDate, YESTERDAY);
        // 재실행이 실제 데이터도 회수 - 미완료 루틴에 날짜별 FAILED 로그가 정확히 1건씩
        for (LocalDate date : List.of(failedDate, YESTERDAY)) {
            List<RoutineLog> logs = routineLogRepository.findByRoutineIdAndRoutineDate(routineId, date);
            assertThat(logs).hasSize(1);
            assertThat(logs.getFirst().getStatus()).isEqualTo(RoutineLogStatus.FAILED);
        }
    }

    // 대상 날짜 이전부터 존재한 DAILY 루틴 - created_at은 auditing이 now로 채워 네이티브로 당김
    private Long persistDailyExistingSince(LocalDate since) {
        User user = userRepository.save(User.signUp());
        Routine routine = routineRepository.save(Routine.create(user, null, "미완료 루틴", AuthType.CHECK,
                "DAILY", null, null, null, null));
        routine.assignOriginToSelf();
        routineRepository.save(routine);
        jdbcTemplate.update("update routines set created_at = ? where id = ?",
                Timestamp.from(since.atTime(LocalTime.NOON).atZone(KST).toInstant()), routine.getId());
        return routine.getId();
    }

    private void seedExecution(LocalDate targetDate, BatchStatus status) {
        JobParameters params = targetDateParams(targetDate);
        JobInstance instance = jobRepository.createJobInstance(RoutineDayEndJobConfig.JOB_NAME, params);
        JobExecution execution = jobRepository.createJobExecution(instance, params, new ExecutionContext());
        execution.setStatus(status);
        execution.setEndTime(LocalDateTime.now());
        jobRepository.update(execution);
    }

    private JobParameters targetDateParams(LocalDate targetDate) {
        return new JobParametersBuilder()
                .addString(RoutineDayEndJobConfig.TARGET_DATE_PARAM, targetDate.toString())
                .toJobParameters();
    }

    private List<LocalDate> targetDatesInInstanceOrder() {
        List<JobInstance> instances = new ArrayList<>(
                jobRepository.getJobInstances(RoutineDayEndJobConfig.JOB_NAME, 0, 100));
        instances.sort((a, b) -> Long.compare(a.getInstanceId(), b.getInstanceId()));
        return instances.stream()
                .map(instance -> LocalDate.parse(jobRepository.getLastJobExecution(instance)
                        .getJobParameters().getString(RoutineDayEndJobConfig.TARGET_DATE_PARAM)))
                .toList();
    }

    private BatchStatus lastStatusOf(LocalDate targetDate) {
        JobInstance instance = jobRepository.getJobInstance(
                RoutineDayEndJobConfig.JOB_NAME, targetDateParams(targetDate));
        return jobRepository.getLastJobExecution(instance).getStatus();
    }

    private long countExecutions() {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from BATCH_JOB_EXECUTION", Long.class);
        return count == null ? 0 : count;
    }
}
