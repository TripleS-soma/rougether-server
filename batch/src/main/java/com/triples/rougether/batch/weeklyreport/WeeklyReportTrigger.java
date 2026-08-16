package com.triples.rougether.batch.weeklyreport;

import com.triples.rougether.batch.dayend.DayEndCompletionChecker;
import com.triples.rougether.infra.llm.LlmClient;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
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

// 주간 회고 트리거. 정본 실행 시각은 일요일 00:30 KST(= 사용자 관점 "토요일 밤")이며, 그 시각에 서버가 죽어 있었거나
// day-end 가 밀려 있었으면 매시 30분·기동 시 다시 시도한다. 대상 주는 "가장 최근에 끝난 일~토"라 어느 요일에 돌아도
// 같은 weekStart 로 수렴하고, 이미 COMPLETED 면 JobInstanceAlreadyCompleteException 으로 조용히 넘어간다.
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyReportTrigger {

    private final JobOperator jobOperator;
    private final Job weeklyReportJob;
    private final DayEndCompletionChecker dayEndCompletionChecker;
    private final LlmClient llmClient;
    private final Clock clock;

    @Scheduled(cron = "0 30 * * * *", zone = "Asia/Seoul")
    public void triggerHourly() {
        runForLatestCompletedWeek();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        runForLatestCompletedWeek();
    }

    void runForLatestCompletedWeek() {
        // LLM_API_KEY 미설정(stub) 환경에서 돌면 가짜 회고가 재생성 불가한 정본으로 남으므로 아예 시작하지 않는다.
        if (!llmClient.isAvailable()) {
            log.warn("주간 회고 batch 보류 - LLM 클라이언트가 stub(LLM_API_KEY 미설정)이라 생성하지 않음");
            return;
        }
        LocalDate weekStart = latestCompletedWeekStart(LocalDate.now(clock));
        LocalDate weekEnd = WeeklyReportJobConfig.weekEndOf(weekStart);
        if (!dayEndCompletionChecker.isCompleted(weekEnd)) {
            log.warn("주간 회고 batch 보류 - 토요일({}) day-end 미완료, 다음 트리거에 재시도", weekEnd);
            return;
        }
        JobParameters jobParameters = new JobParametersBuilder()
                .addString(WeeklyReportJobConfig.WEEK_START_PARAM, weekStart.toString())
                .toJobParameters();
        try {
            JobExecution execution = jobOperator.start(weeklyReportJob, jobParameters);
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                log.error("주간 회고 batch 실행 실패 - weekStart={}, exitStatus={}", weekStart, execution.getExitStatus());
            }
        } catch (JobInstanceAlreadyCompleteException e) {
            log.debug("주간 회고 batch 같은 주 재실행 스킵 - weekStart={}", weekStart);
        } catch (JobExecutionAlreadyRunningException e) {
            // 이전 정각에 시작한 실행이 아직 도는 중(사용자 많음·LLM 지연) — 오류가 아니라 정상 겹침
            log.debug("주간 회고 batch 실행 중이라 이번 트리거 스킵 - weekStart={}", weekStart);
        } catch (Exception e) {
            log.error("주간 회고 batch 실행 실패 - weekStart={}", weekStart, e);
        }
    }

    // 오늘 기준 가장 최근에 끝난 일~토 주의 일요일. 일요일 당일이면 7일 전 일요일(오늘은 다음 주 소속).
    static LocalDate latestCompletedWeekStart(LocalDate today) {
        LocalDate thisWeekSunday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        return thisWeekSunday.minusDays(WeeklyReportJobConfig.WEEK_LENGTH_DAYS);
    }
}
