package com.triples.rougether.batch.dayend;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Component;

// 특정 날짜의 day-end(FAILED 기록) job 이 COMPLETED 인지. 주간 회고처럼 "그날 로그가 확정된 뒤" 돌아야 하는
// 후속 배치의 선행 가드용. day-end job 이름·파라미터는 이 패키지 밖에 노출하지 않고 여기서만 안다.
@Component
@RequiredArgsConstructor
public class DayEndCompletionChecker {

    private final JobRepository jobRepository;

    public boolean isCompleted(LocalDate targetDate) {
        JobExecution last = jobRepository.getLastJobExecution(RoutineDayEndJobConfig.JOB_NAME,
                new JobParametersBuilder()
                        .addString(RoutineDayEndJobConfig.TARGET_DATE_PARAM, targetDate.toString())
                        .toJobParameters());
        return last != null && last.getStatus() == BatchStatus.COMPLETED;
    }
}
