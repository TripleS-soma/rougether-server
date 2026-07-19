package com.triples.rougether.batch.dayend;

import java.time.Clock;
import java.time.LocalDate;
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
public class RoutineDayEndTrigger {

    private final JobOperator jobOperator;
    private final Job routineDayEndJob;
    private final Clock clock;

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void triggerPreviousDay() {
        String targetDate = LocalDate.now(clock).minusDays(1).toString();
        JobParameters jobParameters = new JobParametersBuilder()
                .addString(RoutineDayEndJobConfig.TARGET_DATE_PARAM, targetDate)
                .toJobParameters();
        try {
            jobOperator.start(routineDayEndJob, jobParameters);
        } catch (JobInstanceAlreadyCompleteException e) {
            log.debug("하루 마감 batch 같은 날짜 재실행 스킵 - targetDate={}", targetDate);
        } catch (Exception e) {
            log.warn("하루 마감 batch 실행 실패 - targetDate={}", targetDate, e);
        }
    }
}
