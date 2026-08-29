package com.triples.rougether.batch.eveningdigest;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class EveningDigestTrigger {

    private static final LocalTime READY_TIME = LocalTime.of(21, 0);

    private final JobOperator jobOperator;
    private final Job eveningDigestJob;
    private final Clock clock;

    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Seoul")
    public void triggerHourly() {
        runForToday();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        runForToday();
    }

    void runForToday() {
        LocalDateTime now = LocalDateTime.now(clock);
        if (now.toLocalTime().isBefore(READY_TIME)) {
            log.debug("저녁 미완료 알림 보류 - 발송 시각 전, targetDate={}", now.toLocalDate());
            return;
        }

        LocalDate targetDate = now.toLocalDate();
        JobParameters parameters = new JobParametersBuilder()
                .addString(EveningDigestJobConfig.TARGET_DATE_PARAM, targetDate.toString())
                .toJobParameters();
        try {
            JobExecution execution = jobOperator.start(eveningDigestJob, parameters);
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                log.error("저녁 미완료 알림 batch 실행 실패 - targetDate={}, exitStatus={}",
                        targetDate, execution.getExitStatus());
            }
        } catch (JobInstanceAlreadyCompleteException e) {
            log.debug("저녁 미완료 알림 같은 날짜 재실행 스킵 - targetDate={}", targetDate);
        } catch (JobExecutionAlreadyRunningException e) {
            log.debug("저녁 미완료 알림 batch 실행 중이라 이번 트리거 스킵 - targetDate={}", targetDate);
        } catch (Exception e) {
            log.error("저녁 미완료 알림 batch 실행 실패 - targetDate={}", targetDate, e);
        }
    }
}
