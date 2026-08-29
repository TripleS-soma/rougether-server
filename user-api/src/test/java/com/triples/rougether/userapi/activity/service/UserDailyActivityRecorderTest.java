package com.triples.rougether.userapi.activity.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserDailyActivityRecorderTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock
    private UserDailyActivityWriter writer;

    @Test
    void 같은_KST_날짜의_반복_요청은_한_번만_DB에_기록한다() {
        LocalDate date = LocalDate.of(2026, 8, 29);
        UserDailyActivityRecorder recorder = new UserDailyActivityRecorder(writer, fixedClock(date));

        recorder.record(7L);
        recorder.record(7L);

        verify(writer, times(1)).record(7L, date);
    }

    @Test
    void 같은_사용자의_동시_요청도_한_번만_DB에_기록한다() throws Exception {
        LocalDate date = LocalDate.of(2026, 8, 29);
        UserDailyActivityRecorder recorder = new UserDailyActivityRecorder(writer, fixedClock(date));
        int concurrency = 24;
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(concurrency)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    recorder.record(7L);
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get();
            }
        }

        verify(writer, times(1)).record(7L, date);
    }

    @Test
    void 기록_실패는_요청으로_전파하지_않고_다음_요청에서_재시도한다() {
        LocalDate date = LocalDate.of(2026, 8, 29);
        UserDailyActivityRecorder recorder = new UserDailyActivityRecorder(writer, fixedClock(date));
        doThrow(new IllegalStateException("database unavailable"))
                .when(writer).record(7L, date);

        assertThatCode(() -> recorder.record(7L)).doesNotThrowAnyException();
        assertThatCode(() -> recorder.record(7L)).doesNotThrowAnyException();

        verify(writer, times(2)).record(7L, date);
    }

    @Test
    void UTC_15시를_경계로_KST_활동일이_바뀐다() {
        MutableClock clock = new MutableClock(
                Instant.parse("2026-08-29T14:59:59Z"), KST);
        UserDailyActivityRecorder recorder = new UserDailyActivityRecorder(writer, clock);

        recorder.record(7L);
        clock.setInstant(Instant.parse("2026-08-29T15:00:00Z"));
        recorder.record(7L);

        verify(writer).record(7L, LocalDate.of(2026, 8, 29));
        verify(writer).record(7L, LocalDate.of(2026, 8, 30));
    }

    private Clock fixedClock(LocalDate date) {
        Instant noonKst = date.atTime(12, 0).toInstant(ZoneOffset.ofHours(9));
        return Clock.fixed(noonKst, KST);
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = new AtomicReference<>(instant);
            this.zone = zone;
        }

        private void setInstant(Instant instant) {
            this.instant.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
