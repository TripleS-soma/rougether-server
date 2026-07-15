package com.triples.rougether.batch.reminder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RoutineReminderTrigger {

    private final JobOperator jobOperator;
    private final Job routineReminderJob;
    private final Clock clock;

    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Seoul")
    public void triggerCurrentMinute() {
        String targetMinute = LocalDateTime.now(clock)
                .truncatedTo(ChronoUnit.MINUTES)
                .format(RoutineReminderJobConfig.TARGET_MINUTE_FORMAT);
        JobParameters jobParameters = new JobParametersBuilder()
                .addString(RoutineReminderJobConfig.TARGET_MINUTE_PARAM, targetMinute)
                .toJobParameters();
        try {
            jobOperator.start(routineReminderJob, jobParameters);
        } catch (JobInstanceAlreadyCompleteException e) {
            log.debug("루틴 리마인드 batch 같은 분 재실행 스킵 - targetMinute={}", targetMinute);
        } catch (Exception e) {
            log.warn("루틴 리마인드 batch 실행 실패 - targetMinute={}", targetMinute, e);
        }
    }
}
