package com.triples.rougether.userapi.activity.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
public class DailyActivitySharedCache {

    private static final long INITIAL_RETRY_NANOS = Duration.ofMillis(250).toNanos();
    private static final long MAX_RETRY_NANOS = Duration.ofSeconds(5).toNanos();
    private final StringRedisTemplate redis;
    private final Clock kstClock;
    private final Counter hit;
    private final Counter miss;
    private final Counter bypass;
    private final Counter error;
    private final Timer readTimer;
    private final Timer writeTimer;
    private final Counter opened;
    private final Counter probe;
    private final Counter probeFailed;
    private final Counter recovered;
    private final LongSupplier nanoTime;
    private final AtomicReference<RecoveryState> recovery = new AtomicReference<>(RecoveryState.healthy());

    @Autowired
    public DailyActivitySharedCache(
            @Qualifier("dailyActivityRedis") ObjectProvider<StringRedisTemplate> redis,
            Clock kstClock, MeterRegistry registry) {
        this(redis, kstClock, registry, System::nanoTime);
    }

    DailyActivitySharedCache(ObjectProvider<StringRedisTemplate> redis,
            Clock kstClock, MeterRegistry registry, LongSupplier nanoTime) {
        this.redis = redis.getIfAvailable();
        this.kstClock = kstClock;
        this.nanoTime = nanoTime;
        hit = registry.counter("activity.cache.requests", "outcome", "hit");
        miss = registry.counter("activity.cache.requests", "outcome", "miss");
        bypass = registry.counter("activity.cache.requests", "outcome", "bypass");
        error = registry.counter("activity.cache.errors");
        readTimer = registry.timer("activity.cache.io", "operation", "get");
        writeTimer = registry.timer("activity.cache.io", "operation", "set");
        opened = registry.counter("activity.cache.recovery", "outcome", "opened");
        probe = registry.counter("activity.cache.recovery", "outcome", "probe");
        probeFailed = registry.counter("activity.cache.recovery", "outcome", "probe_failed");
        recovered = registry.counter("activity.cache.recovery", "outcome", "recovered");
    }

    public boolean isRecorded(Long userId, LocalDate date) {
        RecoveryState permit = tryRead();
        if (permit == null) {
            bypass.increment();
            return false;
        }
        try {
            String value = readTimer.record(() -> redis.opsForValue().get(key(userId, date)));
            // miss도 Redis 응답 성공임. 해당 사용자의 DB 기록 여부와 연결 복구를 구분함.
            if (permit.probing() && recovery.compareAndSet(permit, RecoveryState.healthy())) {
                recovered.increment();
            }
            if ("1".equals(value)) {
                hit.increment();
                return true;
            }
            miss.increment();
        } catch (RuntimeException failure) {
            unavailable(permit, failure);
        }
        return false;
    }

    // Writer 프록시가 정상 반환해 DB 커밋이 끝난 경우에만 호출함. Redis는 기록의 원장이 아님.
    public void markRecorded(Long userId, LocalDate date) {
        if (mustBypass()) return;
        RecoveryState permit = recovery.get();
        // DB 커밋 뒤 SET은 복구 확인에 사용하지 않음. 조회 요청만 한 개씩 확인함.
        if (permit.open()) return;
        // 로컬 캐시와 동일하게 당일·전일만 유지하며 자정을 지난 지연 요청도 날짜별로 격리함.
        Duration ttl = Duration.between(kstClock.instant(), date.plusDays(2).atStartOfDay(kstClock.getZone()).toInstant());
        if (ttl.isNegative() || ttl.isZero()) return;
        try {
            writeTimer.record(() -> redis.opsForValue().setIfAbsent(key(userId, date), "1", ttl));
        } catch (RuntimeException failure) {
            unavailable(permit, failure);
        }
    }

    private boolean mustBypass() {
        // 호출자 트랜잭션이 있으면 suspend해도 연결이 반환되지 않으므로 네트워크 캐시를 우회함.
        return redis == null
                || TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive();
    }

    private RecoveryState tryRead() {
        if (mustBypass()) return null;
        RecoveryState state = recovery.get();
        if (!state.open()) return state;
        if (state.probing() || nanoTime.getAsLong() - state.retryAt() < 0) return null;
        RecoveryState candidate = new RecoveryState(true, true, state.retryAt(), state.delay());
        if (!recovery.compareAndSet(state, candidate)) return null;
        probe.increment();
        return candidate;
    }

    static String key(Long userId, LocalDate date) {
        return "rougether:activity:committed:v1:" + date + ":" + userId;
    }

    private void unavailable(RecoveryState permit, RuntimeException failure) {
        error.increment();
        long delay = permit.probing() ? Math.min(permit.delay() * 2, MAX_RETRY_NANOS) : INITIAL_RETRY_NANOS;
        var next = new RecoveryState(true, false, nanoTime.getAsLong() + delay, delay);
        // 발행 당시 상태가 같은 요청만 전이함. 과거 오류가 대기 시간을 연장하거나 복구를 취소하지 않음.
        if (recovery.compareAndSet(permit, next)) {
            if (permit.probing()) {
                probeFailed.increment();
            } else {
                opened.increment();
                log.warn("활동 공유캐시 접근 실패; DB 우회 및 제한된 복구 확인 시작: {}", failure.getClass().getSimpleName());
            }
        }
    }

    private record RecoveryState(boolean open, boolean probing, long retryAt, long delay) {
        static RecoveryState healthy() {
            // 복구마다 새 객체를 사용해 이전 정상 구간에서 발행된 요청과 구분함.
            return new RecoveryState(false, false, 0, 0);
        }
    }
}
