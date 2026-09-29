package com.triples.rougether.batch.dayend;

import com.triples.rougether.batch.support.KstMidnightGuard;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.triples.rougether.batch.alert.BatchFailureAlertNotifier;
import com.triples.rougether.batch.config.BatchJdbcConfig;
import com.triples.rougether.batch.recovery.StaleJobExecutionRecovery;
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
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = DayEndCatchUpIntegrationTest.TestConfig.class)
@ExtendWith(KstMidnightGuard.class)
class DayEndCatchUpIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.triples.rougether.domain")
    @EnableJpaRepositories("com.triples.rougether.domain")
    @EnableJpaAuditing
    @Import({BatchJdbcConfig.class, RoutineDayEndJobConfig.class,
            DayEndCatchUpPlanner.class, RoutineDayEndTrigger.class, StaleJobExecutionRecovery.class})
    static class TestConfig {

        @Bean
        Clock kstClock() {
            return Clock.system(ZoneId.of("Asia/Seoul"));
        }
    }

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate YESTERDAY = LocalDate.now(KST).minusDays(1);

    private static final String STEP_NAME = "routineDayEndFailStep";

    @MockitoBean
    private BatchFailureAlertNotifier alertNotifier;
    @Autowired
    private RoutineDayEndTrigger trigger;
    @Autowired
    private StaleJobExecutionRecovery recovery;
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

    @Test
    void 죽은_STARTED_실행은_막히고_알림되며_정리_후_catch_up이_그_날짜를_다시_처리한다() {
        seedExecution(YESTERDAY.minusDays(1), BatchStatus.COMPLETED);
        JobExecution dead = seedRunningExecution(RoutineDayEndJobConfig.JOB_NAME,
                targetDateParams(YESTERDAY), BatchStatus.STARTED);

        // 정리 전 - 같은 instance 가 실행 중으로 남아 재실행이 막히고 운영 알림이 1회 호출됨
        trigger.triggerDayEnd();
        assertThat(lastStatusOf(YESTERDAY)).isEqualTo(BatchStatus.STARTED);
        verify(alertNotifier, times(1)).notifyFailure(
                eq(RoutineDayEndJobConfig.JOB_NAME + "|" + YESTERDAY), anyString(), anyString());

        assertThat(recovery.recoverStaleExecutions()).isEqualTo(1);

        JobExecution recovered = jobRepository.getJobExecution(dead.getId());
        assertThat(recovered.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(recovered.getExitStatus().getExitCode()).isEqualTo(ExitStatus.FAILED.getExitCode());
        assertThat(recovered.getExitStatus().getExitDescription()).contains("batch 기동 시 정리");
        assertThat(recovered.getEndTime()).isNotNull();
        StepExecution recoveredStep = recovered.getStepExecutions().iterator().next();
        assertThat(recoveredStep.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(recoveredStep.getExitStatus().getExitCode()).isEqualTo(ExitStatus.FAILED.getExitCode());
        assertThat(recoveredStep.getEndTime()).isNotNull();

        trigger.catchUpOnStartup();

        JobInstance instance = jobRepository.getJobInstance(
                RoutineDayEndJobConfig.JOB_NAME, targetDateParams(YESTERDAY));
        assertThat(jobRepository.getJobExecutions(instance)).hasSize(2);
        assertThat(lastStatusOf(YESTERDAY)).isEqualTo(BatchStatus.COMPLETED);
        verify(alertNotifier, times(1)).notifyFailure(anyString(), anyString(), anyString());
    }

    @Test
    void 정리는_모든_job의_실행중_상태만_FAILED로_바꾸고_완료_실패_실행은_건드리지_않는다() {
        seedExecution(YESTERDAY.minusDays(2), BatchStatus.COMPLETED);
        seedExecution(YESTERDAY.minusDays(1), BatchStatus.FAILED);
        JobExecution starting = seedRunningExecution("reminderJob", runParams(1), BatchStatus.STARTING);
        JobExecution stopping = seedRunningExecution("eveningDigestJob", runParams(2), BatchStatus.STOPPING);
        JobExecution started = seedRunningExecution("weeklyReportJob", runParams(3), BatchStatus.STARTED);
        List<String> untouchedBefore = snapshotOf(YESTERDAY.minusDays(2), YESTERDAY.minusDays(1));

        assertThat(recovery.recoverStaleExecutions()).isEqualTo(3);

        for (JobExecution dead : List.of(starting, stopping, started)) {
            JobExecution recovered = jobRepository.getJobExecution(dead.getId());
            assertThat(recovered.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(recovered.getEndTime()).isNotNull();
        }
        assertThat(snapshotOf(YESTERDAY.minusDays(2), YESTERDAY.minusDays(1))).isEqualTo(untouchedBefore);
        // 두 번째 정리는 남은 실행 중 상태가 없어 no-op
        assertThat(recovery.recoverStaleExecutions()).isZero();
        verify(alertNotifier, never()).notifyFailure(anyString(), anyString(), anyString());
    }

    // 실행 중에 프로세스가 죽은 상태 재현 - job·step 모두 끝나지 않은 채 남음
    private JobExecution seedRunningExecution(String jobName, JobParameters params, BatchStatus status) {
        JobInstance instance = jobRepository.createJobInstance(jobName, params);
        JobExecution execution = jobRepository.createJobExecution(instance, params, new ExecutionContext());
        execution.setStatus(status);
        execution.setStartTime(LocalDateTime.now());
        jobRepository.update(execution);
        StepExecution step = jobRepository.createStepExecution(STEP_NAME, execution);
        step.setStatus(BatchStatus.STARTED);
        step.setStartTime(LocalDateTime.now());
        jobRepository.update(step);
        return execution;
    }

    private JobParameters runParams(long runId) {
        return new JobParametersBuilder().addLong("run.id", runId).toJobParameters();
    }

    // 상태·종료 시각·version 스냅샷 - 정리가 행을 다시 쓰면 version 이 올라가 달라짐
    private List<String> snapshotOf(LocalDate... targetDates) {
        List<String> rows = new ArrayList<>();
        for (LocalDate date : targetDates) {
            JobInstance instance = jobRepository.getJobInstance(
                    RoutineDayEndJobConfig.JOB_NAME, targetDateParams(date));
            JobExecution execution = jobRepository.getLastJobExecution(instance);
            rows.add(execution.getId() + "|" + execution.getStatus() + "|" + execution.getEndTime()
                    + "|" + execution.getVersion() + "|" + execution.getLastUpdated());
        }
        return rows;
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
