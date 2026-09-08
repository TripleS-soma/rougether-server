package com.triples.rougether.userapi.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

// 테스트 중 순간을 바꿀 수 있는 Clock. @SpringBootTest에서 @Primary 빈으로 두면 서비스들이 이 시계로 "오늘"을
// 판정하므로 case마다 set()으로 경계 시각을 옮겨 가며 검증할 수 있다. withZone() 파생 시계도 같은 순간을 공유한다
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> current;
    private final ZoneId zone;

    public MutableClock(Instant initial, ZoneId zone) {
        this(new AtomicReference<>(initial), zone);
    }

    private MutableClock(AtomicReference<Instant> shared, ZoneId zone) {
        this.current = shared;
        this.zone = zone;
    }

    public static MutableClock kst(Instant initial) {
        return new MutableClock(initial, TestClocks.KST);
    }

    public void set(Instant instant) {
        current.set(instant);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(current, newZone);
    }

    @Override
    public Instant instant() {
        return current.get();
    }
}
