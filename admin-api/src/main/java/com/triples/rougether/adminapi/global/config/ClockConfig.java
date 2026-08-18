package com.triples.rougether.adminapi.global.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 관측 화면의 주차 경계 계산에 쓰는 KST 기준 Clock. batch/user-api 의 kstClock 과 같은 기준을 쓴다.
@Configuration
public class ClockConfig {

    @Bean
    public Clock kstClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
