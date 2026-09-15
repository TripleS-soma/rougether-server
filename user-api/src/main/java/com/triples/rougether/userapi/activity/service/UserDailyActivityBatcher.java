package com.triples.rougether.userapi.activity.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 요청 스레드 하나가 동시 활동 기록을 모아 커밋하고, 참여 요청은 그 결과까지 기다림. */
@Service
public class UserDailyActivityBatcher {
    public record Activity(Long userId, LocalDate date) {}

    private record Pending(Activity activity, CompletableFuture<Integer> result) {}

    private final UserDailyActivityWriter writer;
    private final boolean enabled;
    private final int batchSize;
    private final int queueCapacity;
    private final long collectNanos;
    private final Counter committedBatches;
    private final Counter failedBatches;
    private final Counter rejected;
    private final DistributionSummary batchUsers;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private boolean writing;

    public UserDailyActivityBatcher(UserDailyActivityWriter writer, MeterRegistry registry,
            @Value("${activity.batch.enabled:false}") boolean enabled,
            @Value("${activity.batch.size:100}") int batchSize,
            @Value("${activity.batch.queue-capacity:512}") int queueCapacity,
            @Value("${activity.batch.collect-millis:2}") int collectMillis) {
        if (batchSize < 1 || batchSize > 256 || queueCapacity < batchSize || queueCapacity > 4096
                || collectMillis < 0 || collectMillis > 10) {
            throw new IllegalArgumentException("활동 batch 크기·대기열·수집 시간이 허용 범위를 벗어났습니다");
        }
        this.writer = writer;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.queueCapacity = queueCapacity;
        collectNanos = TimeUnit.MILLISECONDS.toNanos(collectMillis);
        committedBatches = registry.counter("activity.batch.transactions", "outcome", "committed");
        failedBatches = registry.counter("activity.batch.transactions", "outcome", "failed");
        rejected = registry.counter("activity.batch.rejected");
        batchUsers = registry.summary("activity.batch.users");
        registry.gauge("activity.batch.queued", this, batcher -> batcher.queuedCount());
    }

    private int queuedCount() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    public int record(Long userId, LocalDate date) {
        if (!enabled) return writer.record(userId, date);
        var pending = new Pending(new Activity(userId, date), new CompletableFuture<>());
        lock.lock();
        try {
            if (queue.size() >= queueCapacity) {
                rejected.increment();
                // 무제한 적재하거나 DB 직접 쓰기로 제한을 우회하지 않음. recorder가 실패를 세고 다음 요청에서 재시도함.
                throw new RejectedExecutionException("활동 기록 대기열이 가득 찼습니다");
            }
            queue.addLast(pending);
            while (!pending.result().isDone()) {
                if (!writing && queue.peekFirst() == pending) {
                    writing = true;
                    lock.unlock();
                    try {
                        flush();
                    } finally {
                        lock.lock();
                    }
                } else {
                    // 인터럽트가 와도 이미 접수된 기록의 커밋 결과를 기다리며 인터럽트 상태는 보존함.
                    changed.awaitUninterruptibly();
                }
            }
        } finally {
            lock.unlock();
        }
        return pending.result().join();
    }

    private void flush() {
        // 수집 중에는 DB 연결을 점유하지 않음. 별도 worker나 응답 이후의 메모리 적재 작업도 남기지 않음.
        LockSupport.parkNanos(collectNanos);
        List<Pending> batch = new ArrayList<>(batchSize);
        lock.lock();
        try {
            while (!queue.isEmpty() && batch.size() < batchSize) batch.add(queue.removeFirst());
        } finally {
            lock.unlock();
        }
        try {
            var recorded = writer.recordBatch(batch.stream().map(Pending::activity).toList());
            // 트랜잭션 프록시가 커밋까지 마치고 반환한 뒤에만 호출자를 깨움.
            committedBatches.increment();
            batchUsers.record(batch.size());
            batch.forEach(item -> item.result().complete(recorded.contains(item.activity()) ? 1 : 0));
        } catch (RuntimeException | Error failure) {
            failedBatches.increment();
            batch.forEach(item -> item.result().completeExceptionally(failure));
            if (failure instanceof Error error) throw error;
        } finally {
            lock.lock();
            try {
                writing = false;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }
}
