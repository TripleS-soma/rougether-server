package com.triples.rougether.userapi.market.engine;

import com.triples.rougether.domain.market.entity.MarketEngineLease;
import com.triples.rougether.domain.market.repository.MarketEngineLeaseRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 매칭 엔진 담당 리스(#402). 전체 시스템에서 엔진은 리스를 가진 인스턴스 하나만 돔.
// heartbeat 가 3초마다 리스 행을 잠그고 연장·인수·반납을 결정함. 인수할 때마다 펜싱 번호가 1 오르고,
// 엔진의 처리 트랜잭션은 그 번호를 확인하므로 GC 멈춤 등으로 늦게 깨어난 이전 담당의 쓰기는 거부됨.
@Component
public class EngineLeaseManager implements MarketEngineLeaseGuard {

    private static final Logger log = LoggerFactory.getLogger(EngineLeaseManager.class);

    enum Outcome { EXTENDED, TAKEN_OVER, RELEASED, HELD_BY_OTHER, COOLING_DOWN }

    private final MarketEngineLeaseRepository leaseRepository;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final String ownerToken;
    private final boolean enabled;
    private final Duration leaseDuration;
    private final Duration stallThreshold;
    private volatile boolean held;
    private volatile long fencingToken = -1;
    private volatile Instant lastProgressAt;
    // 정체로 반납한 뒤 이 시각까지는 다시 인수하지 않음(다른 인스턴스에 기회를 줌)
    private volatile Instant noTakeOverUntil;
    private final long heartbeatMillis;
    private ScheduledExecutorService heartbeatExecutor;

    @Autowired
    public EngineLeaseManager(MarketEngineLeaseRepository leaseRepository, Clock clock,
                              PlatformTransactionManager transactionManager,
                              @Value("${market.engine.enabled:false}") boolean enabled,
                              @Value("${market.engine.lease-seconds:10}") long leaseSeconds,
                              @Value("${market.engine.stall-seconds:30}") long stallSeconds,
                              @Value("${market.engine.heartbeat-millis:3000}") long heartbeatMillis) {
        this(leaseRepository, clock, transactionManager, UUID.randomUUID().toString(), enabled,
                Duration.ofSeconds(leaseSeconds), Duration.ofSeconds(stallSeconds), heartbeatMillis);
    }

    // 테스트에서 여러 인스턴스를 흉내낼 때 식별값·시계를 직접 넣음
    EngineLeaseManager(MarketEngineLeaseRepository leaseRepository, Clock clock,
                       PlatformTransactionManager transactionManager, String ownerToken, boolean enabled,
                       Duration leaseDuration, Duration stallThreshold, long heartbeatMillis) {
        this.leaseRepository = leaseRepository;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.ownerToken = ownerToken;
        this.enabled = enabled;
        this.leaseDuration = leaseDuration;
        this.stallThreshold = stallThreshold;
        this.heartbeatMillis = heartbeatMillis;
    }

    // heartbeat 전용 스레드. 공용 taskScheduler(스레드 1개)를 가구 Lambda 발행·봇 활동 등과 나눠 쓰면 앞선 작업이
    // 길어질 때 연장을 못 해 멀쩡한 담당이 인수당하므로 분리함. 스케줄러 빈으로 등록하면 기본 taskScheduler 구성이
    // 바뀌므로 빈이 아닌 자체 실행기로 둠.
    @EventListener(ApplicationReadyEvent.class)
    public void startHeartbeat() {
        if (!enabled) {
            return;
        }
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "market-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatExecutor.scheduleWithFixedDelay(this::heartbeat, 0, heartbeatMillis, TimeUnit.MILLISECONDS);
    }

    void heartbeat() {
        try {
            tick();
        } catch (RuntimeException failure) {
            // 연장에 실패하면 곧 만료되어 다른 인스턴스가 인수함. 믿음은 버리지 않고 펜싱에 맡김.
            log.warn("market engine lease heartbeat failed", failure);
        }
    }

    // heartbeat 1회. 결과를 돌려주고, 이 인스턴스의 믿음(held·번호)은 커밋이 끝난 뒤에 바꿈.
    public Outcome tick() {
        record Decision(Outcome outcome, long token) { }
        Decision decision = transaction.execute(status -> {
            MarketEngineLease lease = leaseRepository.findForUpdate().orElseThrow();
            Instant now = clock.instant();
            if (lease.isOwnedBy(ownerToken)) {
                if (held && isStalled(now)) {
                    lease.release();
                    return new Decision(Outcome.RELEASED, lease.getFencingToken());
                }
                lease.extend(now.plus(leaseDuration));
                return new Decision(Outcome.EXTENDED, lease.getFencingToken());
            }
            if (lease.isExpired(now)) {
                if (noTakeOverUntil != null && now.isBefore(noTakeOverUntil)) {
                    return new Decision(Outcome.COOLING_DOWN, lease.getFencingToken());
                }
                return new Decision(Outcome.TAKEN_OVER, lease.takeOver(ownerToken, now.plus(leaseDuration)));
            }
            return new Decision(Outcome.HELD_BY_OTHER, lease.getFencingToken());
        });
        switch (decision.outcome()) {
            case TAKEN_OVER -> {
                fencingToken = decision.token();
                lastProgressAt = clock.instant();
                held = true;
                log.info("market engine lease taken over (token {})", decision.token());
            }
            case EXTENDED -> {
                // 인수 커밋 결과를 몰랐다가 다시 확인한 경우 등: DB 번호로 맞추고, 담당으로 돌아오면 진전 시각을 새로 셈
                fencingToken = decision.token();
                if (!held) {
                    lastProgressAt = clock.instant();
                }
                held = true;
            }
            case RELEASED -> {
                held = false;
                noTakeOverUntil = clock.instant().plus(leaseDuration);
                log.warn("market engine stalled over {}; lease released", stallThreshold);
            }
            case HELD_BY_OTHER, COOLING_DOWN -> held = false;
        }
        return decision.outcome();
    }

    // 종료할 때 내 리스면 바로 반납해 다음 인스턴스가 유효기간을 기다리지 않게 함(blue/green 인계 공백 축소).
    // 반납은 번호를 올리지 않지만 다음 인수가 올리므로, 멈추는 중인 엔진의 늦은 쓰기는 여전히 펜싱에 막힘.
    @PreDestroy
    public void releaseOnShutdown() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        if (!held) {
            return;
        }
        held = false;
        try {
            transaction.executeWithoutResult(status -> {
                MarketEngineLease lease = leaseRepository.findForUpdate().orElseThrow();
                if (lease.isOwnedBy(ownerToken)) {
                    lease.release();
                }
            });
        } catch (RuntimeException failure) {
            log.warn("market engine lease release on shutdown failed; it will expire", failure);
        }
    }

    @Override
    public boolean holdsLease() {
        return held;
    }

    @Override
    public long fencingToken() {
        return fencingToken;
    }

    @Override
    public void recordProgress() {
        lastProgressAt = clock.instant();
    }

    @Override
    public void fencedOut(long token) {
        if (token == fencingToken) {
            held = false;
        }
    }

    String ownerToken() {
        return ownerToken;
    }

    private boolean isStalled(Instant now) {
        return lastProgressAt != null && Duration.between(lastProgressAt, now).compareTo(stallThreshold) > 0;
    }
}
