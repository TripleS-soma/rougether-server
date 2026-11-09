package com.triples.rougether.userapi.christmas.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ChristmasEventPolicy {
    public static final String CODE = "christmas_2026";
    public static final LocalDate STARTS_ON = LocalDate.of(2026, 11, 9);
    public static final LocalDate ENDS_ON = LocalDate.of(2026, 12, 25);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final boolean enabled;

    public ChristmasEventPolicy(@Value("${christmas.event.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isOpen(Instant now) {
        LocalDate date = LocalDate.ofInstant(now, KST);
        return enabled && !date.isBefore(STARTS_ON) && !date.isAfter(ENDS_ON);
    }
}
