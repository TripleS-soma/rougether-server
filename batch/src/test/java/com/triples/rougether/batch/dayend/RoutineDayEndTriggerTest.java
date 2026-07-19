package com.triples.rougether.batch.dayend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;

// 실행 중 실패 시 이후 날짜를 건너뛰는 중단 분기는 실제 job 실패 유발이 어려워 단위 수준으로 검증
class RoutineDayEndTriggerTest {

    private static final LocalDate D1 = LocalDate.of(2026, 7, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 7, 2);
    private static final LocalDate D3 = LocalDate.of(2026, 7, 3);

    private final JobOperator jobOperator = mock(JobOperator.class);
    private final Job job = mock(Job.class);
    private final DayEndCatchUpPlanner planner = mock(DayEndCatchUpPlanner.class);
    private final RoutineDayEndTrigger trigger = new RoutineDayEndTrigger(jobOperator, job, planner);

    private final List<String> startedDates = new ArrayList<>();

    @BeforeEach
    void stubPlanner() {
        when(planner.planTargetDates()).thenReturn(List.of(D1, D2, D3));
    }

    @Test
    void 날짜_실행이_예외로_실패하면_이후_날짜는_실행하지_않는다() throws Exception {
        stubStart(date -> {
            if (date.equals(D2.toString())) {
                throw new IllegalStateException("job 실행 실패");
            }
            return executionWith(BatchStatus.COMPLETED);
        });

        trigger.triggerDayEnd();

        assertThat(startedDates).containsExactly(D1.toString(), D2.toString());
    }

    @Test
    void 날짜_실행이_FAILED_상태로_끝나면_이후_날짜는_실행하지_않는다() throws Exception {
        stubStart(date -> executionWith(
                date.equals(D2.toString()) ? BatchStatus.FAILED : BatchStatus.COMPLETED));

        trigger.triggerDayEnd();

        assertThat(startedDates).containsExactly(D1.toString(), D2.toString());
    }

    @Test
    void 모든_날짜가_성공하면_끝까지_순서대로_실행한다() throws Exception {
        stubStart(date -> executionWith(BatchStatus.COMPLETED));

        trigger.triggerDayEnd();

        assertThat(startedDates).containsExactly(D1.toString(), D2.toString(), D3.toString());
    }

    private interface StartBehavior {
        JobExecution onStart(String targetDate) throws Exception;
    }

    private void stubStart(StartBehavior behavior) throws Exception {
        when(jobOperator.start(eq(job), any(JobParameters.class))).thenAnswer(invocation -> {
            JobParameters params = invocation.getArgument(1);
            String targetDate = params.getString(RoutineDayEndJobConfig.TARGET_DATE_PARAM);
            startedDates.add(targetDate);
            return behavior.onStart(targetDate);
        });
    }

    private JobExecution executionWith(BatchStatus status) {
        JobExecution execution = mock(JobExecution.class);
        when(execution.getStatus()).thenReturn(status);
        return execution;
    }
}
