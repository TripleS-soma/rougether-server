package com.triples.rougether.batch.weeklyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.batch.dayend.DayEndCompletionChecker;
import com.triples.rougether.infra.llm.LlmClient;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;

class WeeklyReportTriggerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 2026-08-16 은 일요일
    private static final LocalDate SUNDAY = LocalDate.of(2026, 8, 16);

    private final JobOperator jobOperator = mock(JobOperator.class);
    private final Job job = mock(Job.class);
    private final DayEndCompletionChecker checker = mock(DayEndCompletionChecker.class);
    private final LlmClient llmClient = mock(LlmClient.class);

    @BeforeEach
    void llmAvailable() {
        when(llmClient.isAvailable()).thenReturn(true);
    }

    @Test
    void 가장_최근에_끝난_일토_주의_일요일을_고른다() {
        // 일요일 당일: 오늘은 다음 주 소속 → 7일 전 일요일
        assertThat(WeeklyReportTrigger.latestCompletedWeekStart(SUNDAY)).isEqualTo(SUNDAY.minusDays(7));
        // 월~토: 이번 주 일요일 기준 7일 전
        assertThat(WeeklyReportTrigger.latestCompletedWeekStart(SUNDAY.plusDays(1))).isEqualTo(SUNDAY.minusDays(7));
        assertThat(WeeklyReportTrigger.latestCompletedWeekStart(SUNDAY.plusDays(6))).isEqualTo(SUNDAY.minusDays(7));
        // 다음 일요일이 되면 한 주 앞으로 넘어감
        assertThat(WeeklyReportTrigger.latestCompletedWeekStart(SUNDAY.plusDays(7))).isEqualTo(SUNDAY);
    }

    @Test
    void 토요일_day_end가_끝났으면_직전_주_weekStart로_job을_시작한다() throws Exception {
        WeeklyReportTrigger trigger = triggerAt(SUNDAY.atTime(0, 30));
        when(checker.isCompleted(SUNDAY.minusDays(1))).thenReturn(true);
        JobExecution completed = execution(BatchStatus.COMPLETED);
        when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenReturn(completed);

        trigger.triggerHourly();

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(any(Job.class), captor.capture());
        assertThat(captor.getValue().getString(WeeklyReportJobConfig.WEEK_START_PARAM))
                .isEqualTo(SUNDAY.minusDays(7).toString());
    }

    @Test
    void 토요일_day_end가_미완료면_job을_시작하지_않는다() throws Exception {
        WeeklyReportTrigger trigger = triggerAt(SUNDAY.atTime(0, 30));
        when(checker.isCompleted(SUNDAY.minusDays(1))).thenReturn(false);

        trigger.triggerHourly();

        verify(jobOperator, never()).start(any(Job.class), any(JobParameters.class));
    }

    @Test
    void LLM_클라이언트가_stub이면_day_end가_끝났어도_job을_시작하지_않는다() throws Exception {
        WeeklyReportTrigger trigger = triggerAt(SUNDAY.atTime(0, 30));
        when(llmClient.isAvailable()).thenReturn(false);
        when(checker.isCompleted(SUNDAY.minusDays(1))).thenReturn(true);

        trigger.triggerHourly();

        verify(jobOperator, never()).start(any(Job.class), any(JobParameters.class));
    }

    @Test
    void 이미_완료된_주는_예외_없이_넘어간다() throws Exception {
        WeeklyReportTrigger trigger = triggerAt(SUNDAY.plusDays(2).atTime(0, 30));
        when(checker.isCompleted(SUNDAY.minusDays(1))).thenReturn(true);
        when(jobOperator.start(any(Job.class), any(JobParameters.class)))
                .thenThrow(new JobInstanceAlreadyCompleteException("done"));

        trigger.catchUpOnStartup();

        verify(jobOperator).start(any(Job.class), any(JobParameters.class));
    }

    private WeeklyReportTrigger triggerAt(java.time.LocalDateTime kstNow) {
        Instant instant = ZonedDateTime.of(kstNow, KST).toInstant();
        return new WeeklyReportTrigger(jobOperator, job, checker, llmClient, Clock.fixed(instant, KST));
    }

    private static JobExecution execution(BatchStatus status) {
        JobExecution execution = mock(JobExecution.class);
        when(execution.getStatus()).thenReturn(status);
        return execution;
    }
}
