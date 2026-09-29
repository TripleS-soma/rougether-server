package com.triples.rougether.batch.recovery;

import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

// 이전 프로세스가 실행 도중 죽으면(OOM kill 등) 메타데이터에 STARTING/STARTED/STOPPING 실행이 남고,
// 같은 instance 재실행이 JobExecutionAlreadyRunningException 으로 영구히 막힘(#417).
// batch 는 컨테이너 1개로만 뜨고 새 컨테이너는 옛 컨테이너를 내린 뒤 뜨므로, 기동 시점의 실행 중 상태는 모두 죽은 실행임.
// SmartInitializingSingleton 은 모든 싱글톤 생성 직후·컨텍스트 refresh 완료 전에 호출되므로
// 스케줄러 시작(ContextRefreshedEvent)과 ApplicationReadyEvent catch-up 보다 항상 먼저 끝남
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleJobExecutionRecovery implements SmartInitializingSingleton {

    static final String EXIT_DESCRIPTION =
            "batch 기동 시 정리 - 이전 프로세스가 실행 중에 종료되어 FAILED 로 전환";

    private final JobRepository jobRepository;
    private final JobOperator jobOperator;

    @Override
    public void afterSingletonsInstantiated() {
        try {
            recoverStaleExecutions();
        } catch (RuntimeException e) {
            log.error("batch 기동 시 죽은 실행 조회 실패 - 정리 없이 기동 계속", e);
        }
    }

    // 모든 job 이름(메타데이터 기준)에 대해 실행 중 상태로 남은 execution 을 FAILED 로 정리하고 정리 건수를 반환
    public int recoverStaleExecutions() {
        int recovered = 0;
        for (String jobName : jobRepository.getJobNames()) {
            Set<JobExecution> running = jobRepository.findRunningJobExecutions(jobName);
            for (JobExecution execution : running) {
                if (recover(jobName, execution)) {
                    recovered++;
                }
            }
        }
        if (recovered > 0) {
            log.warn("batch 기동 시 죽은 실행 정리 - recovered={}", recovered);
        }
        return recovered;
    }

    // 정리 실패는 기동을 막지 않음 - 남은 실행은 이후 트리거에서 실패로 드러나고 하루 마감은 알림으로 보임
    private boolean recover(String jobName, JobExecution execution) {
        try {
            ExitStatus failed = ExitStatus.FAILED.addExitDescription(EXIT_DESCRIPTION);
            List<StepExecution> runningSteps = execution.getStepExecutions().stream()
                    .filter(step -> step.getStatus().isRunning())
                    .toList();
            runningSteps.forEach(step -> step.setExitStatus(failed));
            execution.setExitStatus(failed);
            // recover 는 실행 중 step·job 을 FAILED + endTime 으로 바꿔 저장함(exit status 는 위에서 채운 값이 함께 저장됨)
            jobOperator.recover(execution);
            log.warn("죽은 batch 실행 FAILED 정리 - jobName={}, executionId={}, steps={}",
                    jobName, execution.getId(), runningSteps.size());
            return true;
        } catch (RuntimeException e) {
            log.error("죽은 batch 실행 정리 실패 - jobName={}, executionId={}",
                    jobName, execution.getId(), e);
            return false;
        }
    }
}
