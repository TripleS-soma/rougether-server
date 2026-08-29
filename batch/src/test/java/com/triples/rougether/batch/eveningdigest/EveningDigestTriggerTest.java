package com.triples.rougether.batch.eveningdigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;

class EveningDigestTriggerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 8, 29);

    private final JobOperator jobOperator = mock(JobOperator.class);
    private final Job job = mock(Job.class);

    @Test
    void 발송_시각_전에는_job을_시작하지_않는다() throws Exception {
        triggerAt(TARGET_DATE.atTime(20, 59)).triggerHourly();

        verify(jobOperator, never()).start(any(Job.class), any(JobParameters.class));
    }

    @Test
    void 발송_시각부터_오늘_날짜로_job을_시작한다() throws Exception {
        JobExecution completed = execution(BatchStatus.COMPLETED);
        when(jobOperator.start(any(Job.class), any(JobParameters.class)))
                .thenReturn(completed);

        triggerAt(TARGET_DATE.atTime(21, 0)).triggerHourly();

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(any(Job.class), captor.capture());
        assertThat(captor.getValue().getString(EveningDigestJobConfig.TARGET_DATE_PARAM))
                .isEqualTo(TARGET_DATE.toString());
    }

    @Test
    void 발송_시각_이후_기동하면_오늘_날짜를_catch_up한다() throws Exception {
        JobExecution completed = execution(BatchStatus.COMPLETED);
        when(jobOperator.start(any(Job.class), any(JobParameters.class)))
                .thenReturn(completed);

        triggerAt(TARGET_DATE.atTime(23, 40)).catchUpOnStartup();

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(any(Job.class), captor.capture());
        assertThat(captor.getValue().getString(EveningDigestJobConfig.TARGET_DATE_PARAM))
                .isEqualTo(TARGET_DATE.toString());
    }

    @Test
    void 다음날_발송_시각_전에는_전날을_소급하지_않는다() throws Exception {
        triggerAt(TARGET_DATE.plusDays(1).atTime(0, 5)).catchUpOnStartup();

        verify(jobOperator, never()).start(any(Job.class), any(JobParameters.class));
    }

    @Test
    void 같은_날짜가_이미_완료됐거나_실행_중이면_예외_없이_넘긴다() throws Exception {
        when(jobOperator.start(any(Job.class), any(JobParameters.class)))
                .thenThrow(new JobInstanceAlreadyCompleteException("done"))
                .thenThrow(new JobExecutionAlreadyRunningException("running"));

        EveningDigestTrigger trigger = triggerAt(TARGET_DATE.atTime(22, 0));

        assertThatCode(trigger::triggerHourly).doesNotThrowAnyException();
        assertThatCode(trigger::triggerHourly).doesNotThrowAnyException();
    }

    private EveningDigestTrigger triggerAt(LocalDateTime kstNow) {
        Instant instant = ZonedDateTime.of(kstNow, KST).toInstant();
        return new EveningDigestTrigger(jobOperator, job, Clock.fixed(instant, KST));
    }

    private static JobExecution execution(BatchStatus status) {
        JobExecution execution = mock(JobExecution.class);
        when(execution.getStatus()).thenReturn(status);
        return execution;
    }
}
