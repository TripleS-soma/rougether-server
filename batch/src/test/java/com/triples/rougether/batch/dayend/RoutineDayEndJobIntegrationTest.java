package com.triples.rougether.batch.dayend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.batch.config.BatchJdbcConfig;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = RoutineDayEndJobIntegrationTest.TestConfig.class)
@SpringBatchTest
class RoutineDayEndJobIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.triples.rougether.domain")
    @EnableJpaRepositories("com.triples.rougether.domain")
    @EnableJpaAuditing
    @Import({BatchJdbcConfig.class, RoutineDayEndJobConfig.class})
    static class TestConfig {
    }

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate YESTERDAY = LocalDate.now(KST).minusDays(1);

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;
    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private RoutineLogRepository routineLogRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.signUp());
    }

    @AfterEach
    void cleanUp() {
        routineLogRepository.deleteAll();
        routineRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void 대상인데_로그가_없는_루틴만_FAILED로_기록한다() throws Exception {
        LocalDate targetDate = YESTERDAY;
        Long targetId = persistDaily("미완료 루틴", targetDate);
        Long completedId = persistDaily("완료한 루틴", targetDate);
        persistCompletedLog(completedId, targetDate);

        runJob(targetDate);

        List<RoutineLog> logs = routineLogRepository.findByRoutineIdAndRoutineDate(targetId, targetDate);
        assertThat(logs).hasSize(1);
        RoutineLog failed = logs.getFirst();
        assertThat(failed.getStatus()).isEqualTo(RoutineLogStatus.FAILED);
        assertThat(failed.getCompletedAt()).isNull();
        assertThat(failed.getRewardAmount()).isZero();
        // 완료 로그가 있는 루틴에는 FAILED가 추가되지 않음
        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(completedId, targetDate))
                .extracting(RoutineLog::getStatus)
                .containsExactly(RoutineLogStatus.COMPLETED);
    }

    @Test
    void WEEKLY_반복요일이_아닌_루틴은_기록하지_않는다() throws Exception {
        LocalDate targetDate = YESTERDAY.minusDays(1);
        String targetToken = targetDate.getDayOfWeek().name().substring(0, 3);
        String otherToken = targetDate.getDayOfWeek().plus(3).name().substring(0, 3);
        Long targetId = persistWeekly("대상 요일 루틴", targetToken, targetDate);
        Long offDayId = persistWeekly("다른 요일 루틴", otherToken, targetDate);

        runJob(targetDate);

        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(targetId, targetDate)).hasSize(1);
        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(offDayId, targetDate)).isEmpty();
    }

    @Test
    void 기간_밖이거나_그날_중_삭제된_루틴은_기록하지_않는다() throws Exception {
        LocalDate targetDate = YESTERDAY.minusDays(2);
        // ends_on이 대상 날짜 전날이라 기간 밖
        Long endedId = persistRoutine("종료된 루틴", "DAILY", null, null, targetDate.minusDays(1), targetDate);
        // 대상 날짜 낮에 삭제됨 - 그날 마감 시점(다음날 00:00)에 유효하지 않음
        Long deletedThatDayId = persistDaily("그날 삭제 루틴", targetDate);
        softDeleteAt(deletedThatDayId, targetDate.atTime(LocalTime.NOON).atZone(KST).toInstant());
        // 마감 이후(지금) 삭제됨 - 그날 마감 시점에는 유효했으므로 대상
        Long deletedLaterId = persistDaily("이후 삭제 루틴", targetDate);
        softDeleteAt(deletedLaterId, Instant.now());

        runJob(targetDate);

        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(endedId, targetDate)).isEmpty();
        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(deletedThatDayId, targetDate)).isEmpty();
        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(deletedLaterId, targetDate)).hasSize(1);
    }

    @Test
    void 버전_분기된_루틴은_그날_유효했던_새_버전에만_기록한다() throws Exception {
        LocalDate targetDate = YESTERDAY.minusDays(3);
        // 옛 버전: 대상 날짜 낮에 닫힘
        Long oldId = persistDaily("옛 버전", targetDate);
        softDeleteAt(oldId, targetDate.atTime(LocalTime.NOON).atZone(KST).toInstant());
        // 새 버전: 대상 날짜에 생성, 같은 계보
        Long newId = persistVersionOf(oldId, "새 버전", targetDate);

        runJob(targetDate);

        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(oldId, targetDate)).isEmpty();
        List<RoutineLog> logs = routineLogRepository.findByRoutineIdAndRoutineDate(newId, targetDate);
        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst().getStatus()).isEqualTo(RoutineLogStatus.FAILED);
    }

    @Test
    void 완료_후_버전_분기된_루틴은_계보에_로그가_있어_기록하지_않는다() throws Exception {
        LocalDate targetDate = YESTERDAY.minusDays(4);
        // 그날 완료(로그는 옛 버전에 남음) 후 버전 분기 - 새 버전 기준으로도 실패가 아님
        Long oldId = persistDaily("완료 후 닫힌 버전", targetDate);
        persistCompletedLog(oldId, targetDate);
        softDeleteAt(oldId, targetDate.atTime(LocalTime.NOON).atZone(KST).toInstant());
        Long newId = persistVersionOf(oldId, "분기된 새 버전", targetDate);

        runJob(targetDate);

        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(newId, targetDate)).isEmpty();
    }

    @Test
    void 같은_날짜_재실행은_JobInstance_유일성으로_막힌다() throws Exception {
        LocalDate targetDate = YESTERDAY.minusDays(5);
        persistDaily("미완료 루틴", targetDate);
        JobParameters params = targetDateParams(targetDate);

        jobOperatorTestUtils.startJob(params);

        assertThatThrownBy(() -> jobOperatorTestUtils.startJob(params))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @Test
    void 강제_재실행에도_로그_부재_필터로_중복_insert가_없다() throws Exception {
        LocalDate targetDate = YESTERDAY.minusDays(6);
        Long targetId = persistDaily("미완료 루틴", targetDate);

        // run 파라미터로 인스턴스 유일성을 우회해 같은 날짜를 실제로 두 번 돌림
        jobOperatorTestUtils.startStep("routineDayEndFailStep", rerunParams(targetDate), new ExecutionContext());
        jobOperatorTestUtils.startStep("routineDayEndFailStep", rerunParams(targetDate), new ExecutionContext());

        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(targetId, targetDate)).hasSize(1);
    }

    @Test
    void 페이지_크기를_초과하는_대상도_모두_기록한다() throws Exception {
        // writer가 만든 로그가 NOT EXISTS 필터에서 후보를 즉시 줄이므로 커서 회귀를 페이지(200) 초과 건수로 잡음
        LocalDate targetDate = YESTERDAY.minusDays(7);
        int count = 250;
        for (int i = 0; i < count; i++) {
            persistDaily("루틴" + i, targetDate);
        }

        runJob(targetDate);

        Long failedCount = jdbcTemplate.queryForObject(
                "select count(*) from routine_logs where status = 'FAILED' and routine_date = ?",
                Long.class, targetDate);
        assertThat(failedCount).isEqualTo(count);
    }

    private void runJob(LocalDate targetDate) throws Exception {
        JobExecution execution = jobOperatorTestUtils.startJob(targetDateParams(targetDate));
        assertThat(execution.getStatus()).isEqualTo(org.springframework.batch.core.BatchStatus.COMPLETED);
    }

    private JobParameters targetDateParams(LocalDate targetDate) {
        return new JobParametersBuilder()
                .addString(RoutineDayEndJobConfig.TARGET_DATE_PARAM, targetDate.toString())
                .toJobParameters();
    }

    private JobParameters rerunParams(LocalDate targetDate) {
        return new JobParametersBuilder()
                .addString(RoutineDayEndJobConfig.TARGET_DATE_PARAM, targetDate.toString())
                .addString("run", UUID.randomUUID().toString())
                .toJobParameters();
    }

    private Long persistDaily(String title, LocalDate targetDate) {
        return persistRoutine(title, "DAILY", null, null, null, targetDate);
    }

    private Long persistWeekly(String title, String dayToken, LocalDate targetDate) {
        return persistRoutine(title, "WEEKLY", "{\"daysOfWeek\":[\"" + dayToken + "\"]}", null, null, targetDate);
    }

    // 생성일을 대상 날짜 9일 전으로 당겨 그날 시점에 이미 존재했던 루틴으로 만듦
    private Long persistRoutine(String title, String repeatType, String repeatDays,
                                LocalDate startsOn, LocalDate endsOn, LocalDate targetDate) {
        Routine routine = routineRepository.save(Routine.create(user, null, title, AuthType.CHECK,
                repeatType, repeatDays, null, startsOn, endsOn));
        routine.assignOriginToSelf();
        routineRepository.save(routine);
        setCreatedAt(routine.getId(), targetDate.minusDays(9).atTime(LocalTime.NOON).atZone(KST).toInstant());
        return routine.getId();
    }

    // oldId 계보의 새 버전을 대상 날짜에 생성된 것으로 만듦
    private Long persistVersionOf(Long oldId, String title, LocalDate targetDate) {
        Routine origin = routineRepository.findById(oldId).orElseThrow();
        Routine newVersion = routineRepository.save(origin.copyAsNewVersion(
                null, title, null, null, null, null, null, null));
        setCreatedAt(newVersion.getId(), targetDate.atTime(LocalTime.NOON).atZone(KST).toInstant());
        return newVersion.getId();
    }

    private void persistCompletedLog(Long routineId, LocalDate date) {
        Routine routine = routineRepository.findById(routineId).orElseThrow();
        routineLogRepository.save(RoutineLog.complete(routine, date, Instant.now(), CurrencyType.COIN, 0));
    }

    private void softDeleteAt(Long routineId, Instant deletedAt) {
        jdbcTemplate.update("update routines set deleted_at = ? where id = ?",
                Timestamp.from(deletedAt), routineId);
    }

    private void setCreatedAt(Long routineId, Instant createdAt) {
        jdbcTemplate.update("update routines set created_at = ? where id = ?",
                Timestamp.from(createdAt), routineId);
    }
}
