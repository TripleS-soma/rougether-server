package com.triples.rougether.batch.weeklyreport;

import com.triples.rougether.domain.report.WeeklyReportPolicy;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 주간 회고 push 트리거. 정본 발송 시각은 주가 끝난 뒤 첫 일요일 20:00 KST(WeeklyReportPolicy.PUSH_TIME)이며,
// 그 시각에 서버가 죽어 있었으면 매시 정각·기동 시 다시 시도한다. 대상은 항상 "가장 최근에 끝난 일~토" 하나뿐이라
// 오래 죽었다 살아나도 지난 주들의 뒤늦은 push 는 보내지 않고 자연 만료되며, 이미 COMPLETED 면
// JobInstanceAlreadyCompleteException 으로 조용히 넘어간다. 이미 저장된 회고만 다루므로 LLM 가용성 게이트는 없다.
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyReportPushTrigger {

    private final JobOperator jobOperator;
    private final Job weeklyReportPushJob;
    private final JobRepository jobRepository;
    private final Clock clock;

    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Seoul")
    public void triggerHourly() {
        runForLatestCompletedWeek();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        runForLatestCompletedWeek();
    }

    void runForLatestCompletedWeek() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate weekStart = WeeklyReportPolicy.latestCompletedWeekStart(now.toLocalDate());
        if (now.isBefore(WeeklyReportPolicy.pushReadyAt(weekStart))) {
            log.debug("주간 회고 push 보류 - 발송 시각 전, weekStart={}", weekStart);
            return;
        }
        // 회고 생성 job 이 그 주를 COMPLETED 하기 전이면 시작하지 않는다 — push job 은 주당 JobInstance 1개라
        // 빈 상태로 COMPLETED 되면 뒤늦게 생성된 회고의 push 가 영구 누락된다(day-end 밀림·LLM 장애로 생성이
        // 발송 시각을 넘긴 복구 시나리오). 생성이 끝내 안 도는 주는 보낼 것도 없고 다음 주 대상 전환으로 자연 만료된다.
        if (!isGenerationCompleted(weekStart)) {
            log.warn("주간 회고 push 보류 - weekStart={} 회고 생성 미완료, 다음 트리거에 재시도", weekStart);
            return;
        }
        JobParameters jobParameters = new JobParametersBuilder()
                .addString(WeeklyReportPushJobConfig.WEEK_START_PARAM, weekStart.toString())
                .toJobParameters();
        try {
            JobExecution execution = jobOperator.start(weeklyReportPushJob, jobParameters);
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                log.error("주간 회고 push batch 실행 실패 - weekStart={}, exitStatus={}",
                        weekStart, execution.getExitStatus());
            }
        } catch (JobInstanceAlreadyCompleteException e) {
            log.debug("주간 회고 push batch 같은 주 재실행 스킵 - weekStart={}", weekStart);
        } catch (JobExecutionAlreadyRunningException e) {
            // 이전 정각에 시작한 실행이 아직 도는 중 - 오류가 아니라 정상 겹침
            log.debug("주간 회고 push batch 실행 중이라 이번 트리거 스킵 - weekStart={}", weekStart);
        } catch (Exception e) {
            log.error("주간 회고 push batch 실행 실패 - weekStart={}", weekStart, e);
        }
    }

    // 운영 생성 job 의 파라미터는 weekStart 하나라 그 인스턴스의 마지막 실행 상태로 판정한다
    private boolean isGenerationCompleted(LocalDate weekStart) {
        JobExecution generation = jobRepository.getLastJobExecution(WeeklyReportJobConfig.JOB_NAME,
                new JobParametersBuilder()
                        .addString(WeeklyReportJobConfig.WEEK_START_PARAM, weekStart.toString())
                        .toJobParameters());
        return generation != null && generation.getStatus() == BatchStatus.COMPLETED;
    }
}
