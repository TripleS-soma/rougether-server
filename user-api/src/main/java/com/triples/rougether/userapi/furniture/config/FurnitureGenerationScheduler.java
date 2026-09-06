package com.triples.rougether.userapi.furniture.config;

import com.triples.rougether.userapi.furniture.service.FurnitureGenerationWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "furniture.generation", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class FurnitureGenerationScheduler {
    private final FurnitureGenerationWorker worker;

    // 전용 scheduler 등록으로 Boot 기본 scheduler가 사라져 기존 작업이 같은 pool을 쓰지 않도록 함.
    // JPA가 초기화 중 executor를 조회할 때 worker → repository를 먼저 생성하지 않도록 static으로 분리한다.
    @Bean(name = "taskScheduler")
    @ConditionalOnMissingBean(name = "taskScheduler")
    static ThreadPoolTaskScheduler applicationTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("scheduling-");
        return scheduler;
    }

    @Bean
    static ThreadPoolTaskScheduler furnitureTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("furniture-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    @Scheduled(fixedDelayString = "${furniture.generation.poll-delay:2s}", scheduler = "furnitureTaskScheduler")
    public void tick() {
        try { worker.runNext(); }
        catch (RuntimeException e) { log.warn("가구 작업 조회 실패 - 다음 tick에서 재확인"); }
    }

    @Scheduled(fixedDelayString = "60s", scheduler = "furnitureTaskScheduler")
    public void cleanup() { worker.maintain(); }
}
