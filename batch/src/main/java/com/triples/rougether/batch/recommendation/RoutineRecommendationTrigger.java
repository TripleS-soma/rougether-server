package com.triples.rougether.batch.recommendation;

import com.triples.rougether.batch.dayend.DayEndCompletionChecker;
import com.triples.rougether.domain.report.WeeklyReportPolicy;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 조정 추천 트리거(#329). 정본 실행 시각은 일요일 새벽(토요일 day-end 완료 직후)이며, 그 시각에 서버가 죽어
// 있었거나 day-end 가 밀려 있었으면 매시 45분·기동 시 다시 시도한다. 대상 주는 "가장 최근에 끝난 일~토"라
// 어느 요일에 돌아도 같은 weekStart 로 수렴하고, 이미 COMPLETED 면 조용히 넘어간다.
// 회고(매시 30분)·회고 push(매시 정각) 트리거와 분을 나눠 같은 시각 동시 기동을 피한다. LLM 무관이라 가용성 게이트 없음.
@Slf4j
@Component
@RequiredArgsConstructor
public class RoutineRecommendationTrigger {

    private final JobOperator jobOperator;
    private final Job routineRecommendationJob;
    private final DayEndCompletionChecker dayEndCompletionChecker;
    private final Clock clock;

    @Scheduled(cron = "0 45 * * * *", zone = "Asia/Seoul")
    public void triggerHourly() {
        runForLatestCompletedWeek();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        runForLatestCompletedWeek();
    }

    void runForLatestCompletedWeek() {
        LocalDate weekStart = WeeklyReportPolicy.latestCompletedWeekStart(LocalDate.now(clock));
        LocalDate weekEnd = WeeklyReportPolicy.weekEndOf(weekStart);
        // 대상 주 토요일 day-end 미완료면 FAILED log 가 아직 없어 룰이 실패 패턴을 놓친다 — 완료 후에만 시작
        if (!dayEndCompletionChecker.isCompleted(weekEnd)) {
            log.warn("조정 추천 batch 보류 - 토요일({}) day-end 미완료, 다음 트리거에 재시도", weekEnd);
            return;
        }
        JobParameters jobParameters = new JobParametersBuilder()
                .addString(RoutineRecommendationJobConfig.WEEK_START_PARAM, weekStart.toString())
                .toJobParameters();
        try {
            JobExecution execution = jobOperator.start(routineRecommendationJob, jobParameters);
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                log.error("조정 추천 batch 실행 실패 - weekStart={}, exitStatus={}", weekStart, execution.getExitStatus());
            }
        } catch (JobInstanceAlreadyCompleteException e) {
            log.debug("조정 추천 batch 같은 주 재실행 스킵 - weekStart={}", weekStart);
        } catch (JobExecutionAlreadyRunningException e) {
            log.debug("조정 추천 batch 실행 중이라 이번 트리거 스킵 - weekStart={}", weekStart);
        } catch (Exception e) {
            log.error("조정 추천 batch 실행 실패 - weekStart={}", weekStart, e);
        }
    }
}
