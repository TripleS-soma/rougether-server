package com.triples.rougether.userapi.aiuser.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai-users")
public record AiUserProperties(boolean enabled, String controlToken, Duration leaseDuration,
        Duration scheduleInterval, int dailyJobLimit) {
    public AiUserProperties {
        controlToken = controlToken == null ? "" : controlToken;
        leaseDuration = leaseDuration == null ? Duration.ofMinutes(5) : leaseDuration;
        scheduleInterval = scheduleInterval == null ? Duration.ofHours(1) : scheduleInterval;
        dailyJobLimit = dailyJobLimit == 0 ? 24 : dailyJobLimit;
        if (enabled && (controlToken.length() < 32 || !controlToken.chars().allMatch(c -> c >= 33 && c <= 126)))
            throw new IllegalArgumentException("AI control token은 32자 이상의 ASCII secret이어야 함");
        if (leaseDuration.compareTo(Duration.ofMinutes(1)) < 0 || leaseDuration.compareTo(Duration.ofMinutes(10)) > 0
                || scheduleInterval.compareTo(Duration.ofMinutes(10)) < 0 || dailyJobLimit < 1 || dailyJobLimit > 100)
            throw new IllegalArgumentException("AI 실행 설정이 허용 범위를 벗어남");
    }
}
