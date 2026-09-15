package com.triples.rougether.userapi.activity.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.userapi.activity.cache.DailyActivitySharedCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
    private UserDailyActivityBatcher writer;

    @Mock
    private DailyActivitySharedCache sharedCache;

    @Test
    void 같은_KST_날짜의_반복_요청은_한_번만_DB에_기록한다() {
        LocalDate date = LocalDate.of(2026, 8, 29);
        UserDailyActivityRecorder recorder = new UserDailyActivityRecorder(writer, fixedClock(date), sharedCache, new SimpleMeterRegistry());
        when(writer.record(anyLong(), any())).thenReturn(1);

        recorder.record(7L);
        recorder.record(7L);

        verify(writer, times(1)).record(7L, date);
    }

    @Test
    void 기록_대상이_아니면_캐시에_고정하지_않는다() {
        // 영향 row 0(탈퇴·봇) 을 "기록됨"으로 캐시하면 상태가 바뀌어도 그날 내내 재시도가 막힘.
        LocalDate date = LocalDate.of(2026, 8, 29);
        UserDailyActivityRecorder recorder = new UserDailyActivityRecorder(writer, fixedClock(date), sharedCache, new SimpleMeterRegistry());
        when(writer.record(anyLong(), any())).thenReturn(0);

        recorder.record(7L);
        recorder.record(7L);

        verify(writer, times(2)).record(7L, date);
    }

    @Test
    void 같은_사용자의_동시_요청도_한_번만_DB에_기록한다() throws Exception {
        LocalDate date = LocalDate.of(2026, 8, 29);
        UserDailyActivityRecorder recorder = new UserDailyActivityRecorder(writer, fixedClock(date), sharedCache, new SimpleMeterRegistry());
        when(writer.record(anyLong(), any())).thenReturn(1);
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
        UserDailyActivityRecorder recorder = new UserDailyActivityRecorder(writer, fixedClock(date), sharedCache, new SimpleMeterRegistry());
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
        UserDailyActivityRecorder recorder = new UserDailyActivityRecorder(writer, clock, sharedCache, new SimpleMeterRegistry());
        when(writer.record(anyLong(), any())).thenReturn(1);

        recorder.record(7L);
        clock.setInstant(Instant.parse("2026-08-29T15:00:00Z"));
        recorder.record(7L);

        verify(writer).record(7L, LocalDate.of(2026, 8, 29));
        verify(writer).record(7L, LocalDate.of(2026, 8, 30));
    }

    @Test
    void 공유캐시_적중은_DB_기록을_생략하고_로컬에도_저장한다() {
        LocalDate date = LocalDate.of(2026, 8, 29);
        var recorder = new UserDailyActivityRecorder(writer, fixedClock(date), sharedCache, new SimpleMeterRegistry());
        when(sharedCache.isRecorded(7L, date)).thenReturn(true);

        recorder.record(7L);
        recorder.record(7L);

        org.mockito.Mockito.verifyNoInteractions(writer);
        verify(sharedCache, times(1)).isRecorded(7L, date);
        org.mockito.Mockito.verify(sharedCache, org.mockito.Mockito.never()).markRecorded(anyLong(), any());
    }

    @Test
    void 처리_중에는_공유캐시에_완료를_표시하지_않는다() throws Exception {
        LocalDate date = LocalDate.of(2026, 8, 29);
        var recorder = new UserDailyActivityRecorder(writer, fixedClock(date), sharedCache, new SimpleMeterRegistry());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(writer.record(7L, date)).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("writer 해제 지연");
            return 1;
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> recorder.record(7L));
            try {
                org.assertj.core.api.Assertions.assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                recorder.record(7L);
                org.mockito.Mockito.verify(sharedCache, org.mockito.Mockito.never()).markRecorded(anyLong(), any());
            } finally {
                release.countDown();
            }
            pending.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        verify(writer, times(1)).record(7L, date);
        verify(sharedCache).markRecorded(7L, date);
    }

    @Test
    void DB_실패와_기록_제외는_공유_완료_표시를_남기지_않고_재시도한다() {
        LocalDate date = LocalDate.of(2026, 8, 29);
        var recorder = new UserDailyActivityRecorder(writer, fixedClock(date), sharedCache, new SimpleMeterRegistry());
        when(writer.record(7L, date)).thenThrow(new IllegalStateException("commit failed")).thenReturn(0, 1);
        recorder.record(7L);
        recorder.record(7L);
        org.mockito.Mockito.verify(sharedCache, org.mockito.Mockito.never()).markRecorded(anyLong(), any());
        recorder.record(7L);
        recorder.record(7L);
        verify(writer, times(3)).record(7L, date);
        verify(sharedCache, times(1)).markRecorded(7L, date);
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
