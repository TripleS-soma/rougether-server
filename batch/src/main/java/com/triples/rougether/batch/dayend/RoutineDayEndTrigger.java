package com.triples.rougether.batch.dayend;

import com.triples.rougether.batch.alert.BatchFailureAlertNotifier;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RoutineDayEndTrigger {

    private final JobOperator jobOperator;
    private final Job routineDayEndJob;
    private final DayEndCatchUpPlanner dayEndCatchUpPlanner;
    private final BatchFailureAlertNotifier alertNotifier;

    @Scheduled(cron = "0 3 * * * *", zone = "Asia/Seoul")
    public void triggerDayEnd() {
        runPendingDates();
    }

    // 자정에 서버가 죽어 있던 경우 보완 - 기동 시 동일 gap 검사 1회.
    // 이전 프로세스가 남긴 실행 중 상태는 StaleJobExecutionRecovery 가 컨텍스트 refresh 중에 먼저 정리함
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        runPendingDates();
    }

    private void runPendingDates() {
        List<LocalDate> targetDates = dayEndCatchUpPlanner.planTargetDates();
        if (targetDates.size() >= 2) {
            log.warn("하루 마감 batch catch-up 수행 - gapDays={}, targetDate={}~{}",
                    targetDates.size(), targetDates.getFirst(), targetDates.getLast());
        }
        for (LocalDate targetDate : targetDates) {
            if (!runFor(targetDate)) {
                // 실패한 날짜에서 중단해야 마지막 성공 날짜가 그 앞에 머물러 다음 트리거가 같은 날짜부터 재시도함
                return;
            }
        }
    }

    private boolean runFor(LocalDate targetDate) {
        JobParameters jobParameters = new JobParametersBuilder()
                .addString(RoutineDayEndJobConfig.TARGET_DATE_PARAM, targetDate.toString())
                .toJobParameters();
        try {
            JobExecution execution = jobOperator.start(routineDayEndJob, jobParameters);
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                log.error("하루 마감 batch 실행 실패 - targetDate={}, exitStatus={}",
                        targetDate, execution.getExitStatus());
                alertFailure(targetDate, "status=" + execution.getStatus()
                        + ", exitCode=" + execution.getExitStatus().getExitCode());
                return false;
            }
            return true;
        } catch (JobInstanceAlreadyCompleteException e) {
            log.debug("하루 마감 batch 같은 날짜 재실행 스킵 - targetDate={}", targetDate);
            return true;
        } catch (Exception e) {
            log.error("하루 마감 batch 실행 실패 - targetDate={}", targetDate, e);
            alertFailure(targetDate, e.getClass().getSimpleName());
            return false;
        }
    }

    // 실패한 날짜는 매시 재시도되므로 날짜별 key 로 묶어 notifier cooldown 동안 한 번만 알림.
    // 알림 오류가 다음 트리거 흐름을 막지 않도록 여기서도 삼킴
    private void alertFailure(LocalDate targetDate, String reason) {
        try {
            alertNotifier.notifyFailure(
                    RoutineDayEndJobConfig.JOB_NAME + "|" + targetDate,
                    "하루 마감(" + RoutineDayEndJobConfig.JOB_NAME + ") 실패",
                    "targetDate=" + targetDate + ", " + reason + " - 매시 3분 재시도");
        } catch (RuntimeException e) {
            log.warn("하루 마감 실패 알림 호출 실패 - targetDate={}", targetDate, e);
        }
    }
}
