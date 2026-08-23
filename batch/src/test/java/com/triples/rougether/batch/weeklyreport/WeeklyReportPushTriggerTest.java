package com.triples.rougether.batch.weeklyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.mockito.ArgumentMatchers.eq;

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
import org.springframework.batch.core.repository.JobRepository;

class WeeklyReportPushTriggerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 2026-08-16 은 일요일. 발송 대상 주는 직전 주(2026-08-09 ~ 2026-08-15)다.
    private static final LocalDate SUNDAY = LocalDate.of(2026, 8, 16);

    private final JobOperator jobOperator = mock(JobOperator.class);
    private final Job job = mock(Job.class);
    private final JobRepository jobRepository = mock(JobRepository.class);

    // 대상 주의 회고 생성 job 이 COMPLETED 인 상태를 만든다 — 발송 게이트 통과 전제.
    // execution() 은 내부에서 stubbing 하므로 thenReturn 인자 안에서 호출하면 중첩 stubbing 오류 — 변수로 먼저 만든다
    private void generationCompleted() {
        JobExecution generation = execution(BatchStatus.COMPLETED);
        when(jobRepository.getLastJobExecution(eq(WeeklyReportJobConfig.JOB_NAME), any(JobParameters.class)))
                .thenReturn(generation);
    }

    @Test
    void 발송_시각_전에는_job을_시작하지_않는다() throws Exception {
        // 새벽 생성 시각(00:30)과 발송 시각 직전(19:59) 모두 보류
        triggerAt(SUNDAY.atTime(0, 30)).triggerHourly();
        triggerAt(SUNDAY.atTime(19, 59)).triggerHourly();

        verify(jobOperator, never()).start(any(Job.class), any(JobParameters.class));
    }

    @Test
    void 회고_생성이_완료되지_않은_주는_발송_시각이_지나도_보류한다() throws Exception {
        // 생성 job 실행 기록 없음(null) — day-end 밀림·LLM 장애로 생성이 발송 시각을 넘긴 복구 시나리오
        when(jobRepository.getLastJobExecution(eq(WeeklyReportJobConfig.JOB_NAME), any(JobParameters.class)))
                .thenReturn(null);
        triggerAt(SUNDAY.atTime(20, 0)).triggerHourly();

        // 생성 job 이 FAILED 로 남은 경우도 동일하게 보류
        JobExecution failedGeneration = execution(BatchStatus.FAILED);
        when(jobRepository.getLastJobExecution(eq(WeeklyReportJobConfig.JOB_NAME), any(JobParameters.class)))
                .thenReturn(failedGeneration);
        triggerAt(SUNDAY.atTime(21, 0)).triggerHourly();

        verify(jobOperator, never()).start(any(Job.class), any(JobParameters.class));
    }

    @Test
    void 발송_시각부터_직전_주_weekStart로_job을_시작한다() throws Exception {
        generationCompleted();
        JobExecution completed = execution(BatchStatus.COMPLETED);
        when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenReturn(completed);

        triggerAt(SUNDAY.atTime(20, 0)).triggerHourly();

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(any(Job.class), captor.capture());
        assertThat(captor.getValue().getString(WeeklyReportPushJobConfig.WEEK_START_PARAM))
                .isEqualTo(SUNDAY.minusDays(7).toString());
    }

    @Test
    void 주중_늦은_기동도_같은_주면_직전_주를_대상으로_발송한다() throws Exception {
        // 일요일 20:00 에 서버가 죽어 있었어도 수요일 새벽 기동 catch-up 이 같은 대상 주를 발송한다.
        // 대상은 항상 "가장 최근 끝난 주" 하나뿐이라 그보다 오래된 주의 뒤늦은 push 는 애초에 만들어지지 않는다.
        generationCompleted();
        JobExecution completed = execution(BatchStatus.COMPLETED);
        when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenReturn(completed);

        triggerAt(SUNDAY.plusDays(3).atTime(3, 0)).catchUpOnStartup();

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(any(Job.class), captor.capture());
        assertThat(captor.getValue().getString(WeeklyReportPushJobConfig.WEEK_START_PARAM))
                .isEqualTo(SUNDAY.minusDays(7).toString());
    }

    @Test
    void 다음_주가_시작되면_직전_대상_주는_자연_만료된다() throws Exception {
        // 다음 일요일 00:00~19:59: 가장 최근 끝난 주가 한 주 앞으로 넘어가고 그 주의 발송 시각(20:00)은 아직이라 보류.
        // 지난 대상 주(SUNDAY-7)를 놓쳤어도 다시 잡지 않는다.
        triggerAt(SUNDAY.plusDays(7).atTime(3, 0)).triggerHourly();

        verify(jobOperator, never()).start(any(Job.class), any(JobParameters.class));
    }

    @Test
    void 이미_완료된_주는_예외_없이_넘어간다() throws Exception {
        generationCompleted();
        when(jobOperator.start(any(Job.class), any(JobParameters.class)))
                .thenThrow(new JobInstanceAlreadyCompleteException("done"));

        assertThatCode(() -> triggerAt(SUNDAY.atTime(21, 0)).triggerHourly()).doesNotThrowAnyException();

        verify(jobOperator).start(any(Job.class), any(JobParameters.class));
    }

    @Test
    void 실행_중인_주는_예외_없이_이번_트리거를_스킵한다() throws Exception {
        generationCompleted();
        when(jobOperator.start(any(Job.class), any(JobParameters.class)))
                .thenThrow(new JobExecutionAlreadyRunningException("running"));

        assertThatCode(() -> triggerAt(SUNDAY.atTime(21, 0)).triggerHourly()).doesNotThrowAnyException();
    }

    private WeeklyReportPushTrigger triggerAt(LocalDateTime kstNow) {
        Instant instant = ZonedDateTime.of(kstNow, KST).toInstant();
        return new WeeklyReportPushTrigger(jobOperator, job, jobRepository, Clock.fixed(instant, KST));
    }

    private static JobExecution execution(BatchStatus status) {
        JobExecution execution = mock(JobExecution.class);
        when(execution.getStatus()).thenReturn(status);
        return execution;
    }
}
