package com.triples.rougether.batch.dayend;

import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
class RoutineDayEndJobConfig {

    static final String JOB_NAME = "routineDayEndJob";
    static final String TARGET_DATE_PARAM = "targetDate";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int CHUNK_SIZE = 200;
    private static final int SKIP_LIMIT = 50;

    @Bean
    Job routineDayEndJob(JobRepository jobRepository, Step routineDayEndFailStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(routineDayEndFailStep)
                .build();
    }

    @Bean
    Step routineDayEndFailStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            DayEndCandidateReader dayEndCandidateReader, DayEndFailProcessor dayEndFailProcessor,
            RoutineLogRepository routineLogRepository) {
        return new StepBuilder("routineDayEndFailStep", jobRepository)
                .<Routine, RoutineLog>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(dayEndCandidateReader)
                .processor(dayEndFailProcessor)
                .writer(chunk -> routineLogRepository.saveAll(chunk.getItems()))
                .faultTolerant()
                // 동시 실행 등으로 unique(routine_id, routine_date) 충돌 시 해당 건만 skip.
                // 미기록분은 재실행 시 reader의 로그 부재 필터가 다시 집어 회수함
                .skip(DataIntegrityViolationException.class)
                .skipLimit(SKIP_LIMIT)
                .build();
    }

    @Bean
    @StepScope
    DayEndCandidateReader dayEndCandidateReader(RoutineRepository routineRepository,
            @Value("#{jobParameters['" + TARGET_DATE_PARAM + "']}") String targetDateParam) {
        LocalDate targetDate = LocalDate.parse(targetDateParam);
        // 그날 유효했던 버전 판정 경계 - 캘린더 과거 조회(findEffectiveOnDay)와 동일 기준
        Instant dayEndExclusive = targetDate.plusDays(1).atStartOfDay(KST).toInstant();
        return new DayEndCandidateReader(routineRepository, dayEndExclusive, targetDate);
    }

    @Bean
    @StepScope
    DayEndFailProcessor dayEndFailProcessor(
            @Value("#{jobParameters['" + TARGET_DATE_PARAM + "']}") String targetDateParam) {
        return new DayEndFailProcessor(LocalDate.parse(targetDateParam));
    }
}
