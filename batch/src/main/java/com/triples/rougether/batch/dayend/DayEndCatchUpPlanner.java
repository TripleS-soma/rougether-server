package com.triples.rougether.batch.dayend;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Component;

// day-end job이 처리해야 할 날짜 목록 산출 - Spring Batch 메타데이터의 마지막 COMPLETED
// targetDate 다음 날부터 어제(KST)까지. 실행 기록이 전혀 없으면(최초 도입) 어제만, 소급 없음
@Component
@RequiredArgsConstructor
class DayEndCatchUpPlanner {

    private static final int INSTANCE_PAGE_SIZE = 100;

    private final JobRepository jobRepository;
    private final Clock clock;

    List<LocalDate> planTargetDates() {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        LocalDate lastCompleted = findLastCompletedTargetDate();
        if (lastCompleted == null) {
            return List.of(yesterday);
        }
        if (!lastCompleted.isBefore(yesterday)) {
            return List.of();
        }
        return lastCompleted.plusDays(1).datesUntil(yesterday.plusDays(1)).toList();
    }

    // instance 생성 순서가 targetDate 순이라는 보장이 없어(과거 날짜 수동 실행 등) 전체 스캔으로 최대 성공 날짜를 찾음.
    // instance는 하루 1개 수준이라 스캔 비용은 무시 가능
    private LocalDate findLastCompletedTargetDate() {
        LocalDate max = null;
        for (int start = 0; ; start += INSTANCE_PAGE_SIZE) {
            List<JobInstance> instances = jobRepository.getJobInstances(
                    RoutineDayEndJobConfig.JOB_NAME, start, INSTANCE_PAGE_SIZE);
            if (instances.isEmpty()) {
                return max;
            }
            for (JobInstance instance : instances) {
                JobExecution lastExecution = jobRepository.getLastJobExecution(instance);
                if (lastExecution == null || lastExecution.getStatus() != BatchStatus.COMPLETED) {
                    continue;
                }
                String targetDate = lastExecution.getJobParameters()
                        .getString(RoutineDayEndJobConfig.TARGET_DATE_PARAM);
                if (targetDate == null) {
                    continue;
                }
                LocalDate date = LocalDate.parse(targetDate);
                if (max == null || date.isAfter(max)) {
                    max = date;
                }
            }
        }
    }
}
