package com.triples.rougether.userapi.activity.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DailyActivitySharedCacheTest {
    private static final LocalDate DATE = LocalDate.of(2026, 9, 11);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T14:59:59Z"), ZoneId.of("Asia/Seoul"));
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicLong nanos = new AtomicLong();
    private ValueOperations<String, String> values;
    private DailyActivitySharedCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("dailyActivityRedis", redis);
        cache = new DailyActivitySharedCache(beans.getBeanProvider(StringRedisTemplate.class), clock, registry, nanos::get);
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.clear();
        registry.close();
    }

    @Test
    void 커밋_표시만_적중하고_사용자와_KST_날짜가_분리된다() {
        when(values.get(DailyActivitySharedCache.key(7L, DATE))).thenReturn("1");
        assertThat(cache.isRecorded(7L, DATE)).isTrue();
        assertThat(cache.isRecorded(8L, DATE)).isFalse();
        assertThat(cache.isRecorded(7L, DATE.plusDays(1))).isFalse();
        when(values.get(DailyActivitySharedCache.key(7L, DATE))).thenReturn("in-flight");
        assertThat(cache.isRecorded(7L, DATE)).isFalse();
    }

    @Test
    void 완료_키는_기록일의_다음다음날_KST_자정까지만_유효하다() {
        cache.markRecorded(7L, DATE);
        verify(values).setIfAbsent(DailyActivitySharedCache.key(7L, DATE), "1", Duration.ofSeconds(86401));
        cache.markRecorded(7L, DATE.minusDays(2));
        verify(values, times(1)).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void Redis_조회_장애는_DB로_우회하고_회복_대기_후_다시_조회한다() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("connection lost"));
        assertThat(cache.isRecorded(7L, DATE)).isFalse();
        assertThat(cache.isRecorded(8L, DATE)).isFalse();
        cache.markRecorded(8L, DATE);
        verify(values, times(1)).get(anyString());
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        advanceMillis(249);
        assertThat(cache.isRecorded(9L, DATE)).isFalse();
        verify(values, times(1)).get(anyString());
        advanceMillis(1);
        doReturn("1").when(values).get(anyString());
        assertThat(cache.isRecorded(9L, DATE)).isTrue();
        assertThat(registry.get("activity.cache.errors").counter().count()).isEqualTo(1);
        assertThat(recoveryCount("probe")).isEqualTo(1);
        assertThat(recoveryCount("recovered")).isEqualTo(1);
    }

    @Test
    void Redis_쓰기_장애는_이미_커밋된_DB_기록의_실패로_전파하지_않는다() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("connection lost"));
        assertThatCode(() -> cache.markRecorded(7L, DATE)).doesNotThrowAnyException();
        assertThat(registry.get("activity.cache.errors").counter().count()).isEqualTo(1);
    }

    @Test
    void 호출자_트랜잭션이_있으면_Redis_통신을_하지_않는다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThat(cache.isRecorded(7L, DATE)).isFalse();
        cache.markRecorded(7L, DATE);
        verifyNoInteractions(values);
    }

    @Test
    void 기능을_끄면_Redis_빈_없이_DB_우회를_사용한다() {
        var disabled = new DailyActivitySharedCache(new DefaultListableBeanFactory()
                .getBeanProvider(StringRedisTemplate.class), clock, registry);
        assertThat(disabled.isRecorded(7L, DATE)).isFalse();
        assertThatCode(() -> disabled.markRecorded(7L, DATE)).doesNotThrowAnyException();
        verifyNoInteractions(values);
    }

    @Test
    void 장기_장애의_복구_확인은_지수적으로_늦추고_최대_5초로_제한한다() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("offline"));
        cache.isRecorded(1L, DATE);
        int calls = 1;
        for (long delay : new long[]{250, 500, 1000, 2000, 4000, 5000, 5000}) {
            advanceMillis(delay - 1);
            for (int user = 2; user < 102; user++) assertThat(cache.isRecorded((long) user, DATE)).isFalse();
            verify(values, times(calls)).get(anyString());
            advanceMillis(1);
            assertThat(cache.isRecorded(200L, DATE)).isFalse();
            verify(values, times(++calls)).get(anyString());
        }
        assertThat(recoveryCount("opened")).isEqualTo(1);
        assertThat(recoveryCount("probe_failed")).isEqualTo(7);
    }

    @Test
    void 복구된_뒤_다시_장애가_나면_250ms부터_확인한다() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("offline"));
        cache.isRecorded(1L, DATE);
        advanceMillis(250); cache.isRecorded(2L, DATE);
        advanceMillis(500);
        doReturn("1").when(values).get(anyString());
        assertThat(cache.isRecorded(3L, DATE)).isTrue();
        doThrow(new IllegalStateException("offline again")).when(values).get(anyString());
        cache.isRecorded(4L, DATE);
        advanceMillis(250);
        doReturn("1").when(values).get(anyString());
        assertThat(cache.isRecorded(5L, DATE)).isTrue();
        assertThat(recoveryCount("opened")).isEqualTo(2);
        assertThat(recoveryCount("recovered")).isEqualTo(2);
    }

    @Test
    void 복구_확인이_miss여도_통신은_복구하고_그_사용자는_DB로_보낸다() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("offline"));
        cache.isRecorded(1L, DATE);
        advanceMillis(250);
        doReturn(null).when(values).get(anyString());
        assertThat(cache.isRecorded(2L, DATE)).isFalse();
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        assertThat(recoveryCount("recovered")).isEqualTo(1);
        doReturn("1").when(values).get(DailyActivitySharedCache.key(3L, DATE));
        assertThat(cache.isRecorded(3L, DATE)).isTrue();
        assertThat(recoveryCount("probe")).isEqualTo(1);
    }

    @Test
    void 쓰기_오류로_우회해도_조회_한_개가_복구를_확인한다() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenThrow(new IllegalStateException("offline"));
        cache.markRecorded(1L, DATE);
        advanceMillis(250);
        cache.markRecorded(2L, DATE);
        verify(values, times(1)).setIfAbsent(anyString(), anyString(), any(Duration.class));
        when(values.get(anyString())).thenReturn("1");
        assertThat(cache.isRecorded(3L, DATE)).isTrue();
        assertThat(recoveryCount("recovered")).isEqualTo(1);
    }

    @Test
    void 동시_요청과_쓰기에도_복구_확인은_한_개만_진행한다() throws Exception {
        when(values.get(anyString())).thenThrow(new IllegalStateException("offline"));
        cache.isRecorded(1L, DATE);
        advanceMillis(250);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); await(release); return "1"; }).when(values).get(anyString());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var probing = executor.submit(() -> cache.isRecorded(2L, DATE));
            try {
                await(entered);
                // 확인 스레드가 지연돼도 만료 시각만 보고 추가 확인을 시작하면 안 됨.
                advanceMillis(10_000);
                var others = new ArrayList<java.util.concurrent.Future<Boolean>>();
                for (int i = 0; i < 32; i++) {
                    long user = 10L + i;
                    others.add(executor.submit(() -> {
                        cache.markRecorded(user, DATE);
                        return cache.isRecorded(user, DATE);
                    }));
                }
                for (var other : others) assertThat(other.get(2, TimeUnit.SECONDS)).isFalse();
                verify(values, times(2)).get(anyString());
                verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
            } finally { release.countDown(); }
            assertThat(probing.get(2, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(cache.isRecorded(100L, DATE)).isTrue();
        assertThat(recoveryCount("probe")).isEqualTo(1);
    }

    @Test
    void 복구_후_이전_요청의_늦은_실패는_다시_우회를_열지_않는다() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(values.get(DailyActivitySharedCache.key(1L, DATE))).thenAnswer(call -> {
            entered.countDown(); await(release); throw new IllegalStateException("old failure");
        });
        when(values.get(DailyActivitySharedCache.key(2L, DATE))).thenThrow(new IllegalStateException("outage"));
        when(values.get(DailyActivitySharedCache.key(3L, DATE))).thenReturn("1");
        when(values.get(DailyActivitySharedCache.key(4L, DATE))).thenReturn("1");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var old = executor.submit(() -> cache.isRecorded(1L, DATE));
            try {
                await(entered);
                cache.isRecorded(2L, DATE);
                advanceMillis(250);
                assertThat(cache.isRecorded(3L, DATE)).isTrue();
            } finally { release.countDown(); }
            assertThat(old.get(2, TimeUnit.SECONDS)).isFalse();
        }
        assertThat(cache.isRecorded(4L, DATE)).isTrue();
        assertThat(recoveryCount("opened")).isEqualTo(1);
        assertThat(registry.get("activity.cache.errors").counter().count()).isEqualTo(2);
    }

    @Test
    void 이전_요청의_늦은_성공은_열린_우회를_임의로_해제하지_않는다() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(values.get(DailyActivitySharedCache.key(1L, DATE))).thenAnswer(call -> {
            entered.countDown(); await(release); return "1";
        });
        when(values.get(DailyActivitySharedCache.key(2L, DATE))).thenThrow(new IllegalStateException("outage"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var old = executor.submit(() -> cache.isRecorded(1L, DATE));
            try { await(entered); cache.isRecorded(2L, DATE); }
            finally { release.countDown(); }
            assertThat(old.get(2, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(cache.isRecorded(3L, DATE)).isFalse();
        verify(values, never()).get(DailyActivitySharedCache.key(3L, DATE));
        assertThat(recoveryCount("recovered")).isZero();
    }

    @Test
    void 트랜잭션_동기화_중에는_복구_확인_차례라도_Redis를_사용하지_않는다() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("offline"));
        cache.isRecorded(1L, DATE);
        advanceMillis(250);
        TransactionSynchronizationManager.initSynchronization();
        assertThat(cache.isRecorded(2L, DATE)).isFalse();
        cache.markRecorded(2L, DATE);
        verify(values, times(1)).get(anyString());
        assertThat(recoveryCount("probe")).isZero();
        TransactionSynchronizationManager.clearSynchronization();
        doReturn("1").when(values).get(anyString());
        assertThat(cache.isRecorded(3L, DATE)).isTrue();
    }

    @Test
    void 단조_시간이_long_경계를_넘어도_복구_시점을_판단한다() {
        nanos.set(Long.MAX_VALUE - Duration.ofMillis(100).toNanos());
        when(values.get(anyString())).thenThrow(new IllegalStateException("offline"));
        cache.isRecorded(1L, DATE);
        advanceMillis(249);
        assertThat(cache.isRecorded(2L, DATE)).isFalse();
        verify(values, times(1)).get(anyString());
        advanceMillis(1);
        doReturn("1").when(values).get(anyString());
        assertThat(cache.isRecorded(3L, DATE)).isTrue();
    }

    private void advanceMillis(long millis) { nanos.addAndGet(Duration.ofMillis(millis).toNanos()); }

    private double recoveryCount(String outcome) {
        return registry.get("activity.cache.recovery").tag("outcome", outcome).counter().count();
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("동시성 검사 시간 초과");
    }
}
