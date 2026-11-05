package com.triples.rougether.adminapi.activity.service;

import com.triples.rougether.domain.activity.repository.AdminUserActivityRepository;
import com.triples.rougether.domain.activity.repository.AdminUserActivityRepository.FeatureRecord;
import com.triples.rougether.adminapi.activity.error.AdminActivityBusyException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AdminUserActivityReader {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int MAX_CACHED_DAYS = 8;
    private final AdminUserActivityRepository repository;
    private final Clock clock;
    private final long ttlNanos;
    private final LongSupplier ticker;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<LocalDate, CachedSnapshot> cache = new LinkedHashMap<>(8, 0.75f, true);

    @Autowired
    public AdminUserActivityReader(AdminUserActivityRepository repository, Clock clock,
            @Value("${admin.activity.cache-ttl:15s}") Duration cacheTtl) {
        this(repository, clock, cacheTtl, System::nanoTime);
    }

    AdminUserActivityReader(AdminUserActivityRepository repository, Clock clock, Duration cacheTtl, LongSupplier ticker) {
        if (cacheTtl.isNegative() || cacheTtl.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("활동 집계 캐시는 0~60초 범위여야 합니다.");
        }
        this.repository = repository;
        this.clock = clock;
        this.ttlNanos = cacheTtl.toNanos();
        this.ticker = ticker;
    }

    // 같은 날짜의 검색/페이지 이동은 집계를 공유한다. 날짜가 달라도 동시에 DB 집계를 실행하지 않는다.
    // 트랜잭션은 repository 호출 때만 열려 캐시나 이 잠금을 기다리는 동안 커넥션을 점유하지 않는다.
    public Snapshot read(LocalDate date) {
        try {
            if (!lock.tryLock(200, TimeUnit.MILLISECONDS)) throw new AdminActivityBusyException();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AdminActivityBusyException();
        }
        try {
            return readLocked(date);
        } finally {
            lock.unlock();
        }
    }

    private Snapshot readLocked(LocalDate date) {
        CachedSnapshot cached = cache.get(date);
        if (cached != null && ticker.getAsLong() - cached.storedAtNanos() < ttlNanos) {
            return cached.snapshot();
        }
        List<FeatureRecord> records = repository.findDailyRecords(date,
                date.atStartOfDay(KST).toEpochSecond(), date.plusDays(1).atStartOfDay(KST).toEpochSecond());
        Snapshot snapshot = new Snapshot(clock.instant(), List.copyOf(records));
        if (ttlNanos > 0) {
            cache.put(date, new CachedSnapshot(ticker.getAsLong(), snapshot));
            if (cache.size() > MAX_CACHED_DAYS) cache.pollFirstEntry();
        }
        return snapshot;
    }

    public record Snapshot(Instant generatedAt, List<FeatureRecord> records) {}
    private record CachedSnapshot(long storedAtNanos, Snapshot snapshot) {}
}
