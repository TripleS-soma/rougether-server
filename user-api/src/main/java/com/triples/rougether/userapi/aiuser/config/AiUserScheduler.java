package com.triples.rougether.userapi.aiuser.config;

import com.triples.rougether.userapi.aiuser.service.AiUserJobs;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(AiUserProperties.class)
public class AiUserScheduler {
    private final AiUserJobs jobs;
    @Scheduled(fixedDelayString = "${ai-users.scheduler-delay:60000}")
    public void schedule() { jobs.schedule(); }
}
