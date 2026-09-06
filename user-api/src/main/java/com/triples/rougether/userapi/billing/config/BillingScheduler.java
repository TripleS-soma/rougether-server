package com.triples.rougether.userapi.billing.config;
import com.triples.rougether.userapi.billing.service.FurnitureBillingService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "billing", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class BillingScheduler {
    private final FurnitureBillingService service;
    @Bean(name = "taskScheduler")
    @ConditionalOnMissingBean(name = "taskScheduler")
    static ThreadPoolTaskScheduler applicationTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("scheduling-");
        return scheduler;
    }
    @Bean
    static ThreadPoolTaskScheduler billingTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("billing-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
    @Scheduled(fixedDelayString = "30s", scheduler = "billingTaskScheduler")
    public void consume() { service.consumePending(); }
}
