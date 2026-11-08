package com.triples.rougether.userapi.logingift.service;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LoginGiftCaptureWindow {
    private final Instant startsAt;
    private final Instant endsAt;

    // 행사 밖의 일반 요청은 선물 DB의 가용성에 의존하지 않도록 배포 설정으로 캡처 기간을 제한함.
    public LoginGiftCaptureWindow(
            @Value("${login-gift.capture-starts-at:2026-11-10T15:00:00Z}") String startsAt,
            @Value("${login-gift.capture-ends-at:2026-11-11T15:00:00Z}") String endsAt) {
        this.startsAt = Instant.parse(startsAt);
        this.endsAt = Instant.parse(endsAt);
        if (!this.startsAt.isBefore(this.endsAt)) throw new IllegalArgumentException("접속 선물 캡처 기간이 올바르지 않습니다");
    }

    public boolean accepts(Instant at) {
        return !at.isBefore(startsAt) && at.isBefore(endsAt);
    }
}
