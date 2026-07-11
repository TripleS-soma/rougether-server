package com.triples.rougether.userapi.global.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @Scheduled 활성화 + 스케줄러가 쓰는 KST 기준 Clock 제공
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public Clock kstClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
