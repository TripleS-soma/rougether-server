package com.triples.rougether.userapi.market.engine;

import jakarta.annotation.PreDestroy;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

// 단일 스레드 매칭 엔진(#401). 접수 대장의 PENDING 을 접수 순서대로 하나씩 처리함.
// 스레드 하나가 순서를 확정하므로 같은 매물을 두고 락 경쟁이 없음. market.engine.enabled 가 켜진 인스턴스에서만 돔
// (#402 리스 전까지 운영에서 끔 — blue/green 두 컨테이너가 동시에 돌면 안 됨).
@Component
public class MatchingEngine {

    static final int MAX_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(MatchingEngine.class);

    private final CommandApplier applier;
    private final MarketEngineLeaseGuard leaseGuard;
    private final boolean enabled;
    private final int batchSize;
    private final long pollIntervalMillis;
    private final long retryDelayMillis;
    private final OrderBook book = new OrderBook();
    private volatile boolean running;
    private volatile boolean bookLoaded;
    private Thread thread;

    public MatchingEngine(CommandApplier applier, MarketEngineLeaseGuard leaseGuard,
                          @Value("${market.engine.enabled:false}") boolean enabled,
                          @Value("${market.engine.batch-size:50}") int batchSize,
                          @Value("${market.engine.poll-interval-millis:100}") long pollIntervalMillis,
                          @Value("${market.engine.retry-delay-millis:1000}") long retryDelayMillis) {
        this.applier = applier;
        this.leaseGuard = leaseGuard;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.pollIntervalMillis = pollIntervalMillis;
        this.retryDelayMillis = retryDelayMillis;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "market-matching-engine");
        thread.setDaemon(true);
        thread.start();
        log.info("market matching engine started");
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread.join(5_000);
        }
    }

    // 한 번 폴링해 처리함. 처리한 건수를 돌려주고, 실패하면 -1 (호출 측이 잠시 쉼). 테스트는 이 메서드로 구동함.
    public synchronized int runOnce() {
        if (!leaseGuard.holdsLease()) {
            bookLoaded = false;
            return 0;
        }
        if (!bookLoaded) {
            applier.reload(book);
            bookLoaded = true;
        }
        List<Long> pending = applier.pendingIds(batchSize);
        int processed = 0;
        for (Long commandId : pending) {
            try {
                applier.apply(commandId, book);
                processed++;
            } catch (RuntimeException failure) {
                handleFailure(commandId, failure);
                return -1;
            }
        }
        return processed;
    }

    // 테스트 격리용: 다음 runOnce 에서 호가창을 DB 로 다시 적재함.
    public void invalidateBook() {
        bookLoaded = false;
    }

    private void handleFailure(Long commandId, RuntimeException failure) {
        // 처리 트랜잭션은 롤백됐지만 메모리 호가창은 이미 바뀌었을 수 있음
        bookLoaded = false;
        if (isTransient(failure)) {
            // 데드락·락 대기 초과는 접수 탓이 아니므로 실패 횟수를 쓰지 않고 다시 시도함
            log.info("market command {} hit a transient lock failure; retrying", commandId, failure);
            return;
        }
        int attempts = applier.recordFailure(commandId);
        log.warn("market command {} failed (attempt {})", commandId, attempts, failure);
        if (attempts >= MAX_ATTEMPTS) {
            applier.rejectAsEngineError(commandId);
        }
    }

    static boolean isTransient(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException || cause instanceof PessimisticLockingFailureException
                    || cause instanceof jakarta.persistence.PessimisticLockException
                    || cause instanceof jakarta.persistence.LockTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private void loop() {
        while (running) {
            try {
                int processed = runOnce();
                if (processed < 0) {
                    Thread.sleep(retryDelayMillis);
                } else if (processed == 0) {
                    Thread.sleep(pollIntervalMillis);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable unexpected) {
                // Error 까지 잡아 스레드가 조용히 죽지 않게 함
                bookLoaded = false;
                log.error("market matching engine loop error", unexpected);
                sleepQuietly(retryDelayMillis);
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
