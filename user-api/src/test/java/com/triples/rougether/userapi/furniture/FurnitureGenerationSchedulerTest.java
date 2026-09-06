package com.triples.rougether.userapi.furniture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.triples.rougether.userapi.furniture.config.FurnitureGenerationScheduler;
import com.triples.rougether.userapi.furniture.service.FurnitureGenerationWorker;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class FurnitureGenerationSchedulerTest {
    @Configuration
    @EnableScheduling
    @Import(FurnitureGenerationScheduler.class)
    static class SchedulerContext {
        @Bean FurnitureGenerationWorker worker() { return mock(FurnitureGenerationWorker.class); }
    }

    @Test void 실제_스케줄_등록과_기존_스케줄러_분리() {
        try (var context = new AnnotationConfigApplicationContext(SchedulerContext.class)) {
            var furniture = context.getBean("furnitureTaskScheduler", ThreadPoolTaskScheduler.class);
            var ordinary = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
            assertThat(furniture).isNotSameAs(ordinary);
            assertThat(furniture.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
            assertThat(ordinary.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(2);
        }
    }
}
