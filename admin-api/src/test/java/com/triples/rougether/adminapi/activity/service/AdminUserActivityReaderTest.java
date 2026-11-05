package com.triples.rougether.adminapi.activity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.triples.rougether.adminapi.activity.error.AdminActivityBusyException;
import com.triples.rougether.domain.activity.repository.AdminUserActivityRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AdminUserActivityReaderTest {
    private final AdminUserActivityRepository repository = mock(AdminUserActivityRepository.class);
    private final AtomicLong nanos = new AtomicLong();
    private final LocalDate date = LocalDate.of(2030, 8, 20);
    private final AdminUserActivityReader reader = new AdminUserActivityReader(repository,
            Clock.fixed(Instant.parse("2030-08-20T00:00:00Z"), ZoneOffset.UTC), Duration.ofSeconds(15), nanos::get);

    @Test
    void 성공한빈결과도캐시하고_TTL경계에서다시조회한다() {
        when(repository.findDailyRecords(any(), anyLong(), anyLong())).thenReturn(List.of());
        var first = reader.read(date);
        verify(repository).findDailyRecords(date, Instant.parse("2030-08-19T15:00:00Z").getEpochSecond(),
                Instant.parse("2030-08-20T15:00:00Z").getEpochSecond());
        nanos.set(Duration.ofSeconds(15).toNanos() - 1);
        assertThat(reader.read(date)).isSameAs(first);
        verify(repository, times(1)).findDailyRecords(eq(date), anyLong(), anyLong());
        nanos.incrementAndGet();
        assertThat(reader.read(date)).isNotSameAs(first);
        verify(repository, times(2)).findDailyRecords(eq(date), anyLong(), anyLong());
    }

    @Test
    void 실패는캐시하지않고_최대8일만보관한다() {
        when(repository.findDailyRecords(any(), anyLong(), anyLong())).thenThrow(new IllegalStateException("DB failure"))
                .thenReturn(List.of());
        assertThatThrownBy(() -> reader.read(date)).isInstanceOf(IllegalStateException.class);
        for (int i = 0; i < 9; i++) reader.read(date.minusDays(i));
        reader.read(date);
        verify(repository, times(11)).findDailyRecords(any(), anyLong(), anyLong());
    }

    @Test
    void 집계가진행중이면_다른날짜요청을_짧은대기후거절한다() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(repository.findDailyRecords(any(), anyLong(), anyLong())).thenAnswer(invocation -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return List.of();
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> reader.read(date));
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> reader.read(date.minusDays(1))).isInstanceOf(AdminActivityBusyException.class);
                verify(repository, times(1)).findDailyRecords(any(), anyLong(), anyLong());
            } finally {
                release.countDown();
            }
            pending.get(5, TimeUnit.SECONDS);
        }
        assertThat(reader.read(date).records()).isEmpty();
    }
}
