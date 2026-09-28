package com.triples.rougether.userapi.market.engine;

import com.triples.rougether.domain.market.entity.MarketCommand;
import com.triples.rougether.domain.market.entity.MarketOrder;
import com.triples.rougether.domain.market.repository.MarketCommandRepository;
import com.triples.rougether.domain.market.repository.MarketOrderRepository;
import com.triples.rougether.userapi.global.persistence.UniqueViolations;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 7일 지난 대기 주문 만료(#403). 주문을 직접 고치지 않고 EXPIRE 접수만 넣어 엔진이 처리하게 함(쓰는 주체는 엔진 하나).
// 주문당 request_id "expire-{orderId}" 라 여러 번 돌거나 여러 인스턴스가 겹쳐도 접수는 한 번만 들어감.
@Component
public class MarketOrderExpiryScheduler {

    static final int BATCH_SIZE = 200;
    private static final String REQUEST_UNIQUE = "uk_market_commands_request";
    private static final Logger log = LoggerFactory.getLogger(MarketOrderExpiryScheduler.class);

    private final MarketOrderRepository orderRepository;
    private final MarketCommandRepository commandRepository;
    private final MarketEngineLeaseGuard leaseGuard;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final boolean enabled;

    public MarketOrderExpiryScheduler(MarketOrderRepository orderRepository, MarketCommandRepository commandRepository,
                                      MarketEngineLeaseGuard leaseGuard, Clock clock,
                                      PlatformTransactionManager transactionManager,
                                      @Value("${market.engine.enabled:false}") boolean enabled) {
        this.orderRepository = orderRepository;
        this.commandRepository = commandRepository;
        this.leaseGuard = leaseGuard;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
    }

    // 스위치·리스 확인을 시계보다 먼저 함(엔진이 꺼진 인스턴스·테스트에서는 아무것도 부르지 않음)
    @Scheduled(fixedDelayString = "${market.engine.expiry-delay-millis:60000}")
    public void run() {
        if (!enabled || !leaseGuard.holdsLease()) {
            return;
        }
        try {
            expireDue();
            long stuck = transaction.execute(status -> orderRepository.countStuckExpired(clock.instant()));
            if (stuck > 0) {
                log.warn("market orders expired but still open after a rejected expiry: {} (manual check needed)", stuck);
            }
        } catch (RuntimeException failure) {
            log.warn("market order expiry run failed", failure);
        }
    }

    // 만료 접수를 넣은 건수를 돌려줌. 접수마다 트랜잭션을 따로 써서 한 건의 실패가 다른 건을 막지 않음.
    public int expireDue() {
        Instant now = clock.instant();
        List<MarketOrder> due = transaction.execute(status -> orderRepository
                .findExpirable(now, PageRequest.of(0, BATCH_SIZE)));
        int inserted = 0;
        for (MarketOrder order : due) {
            if (enqueue(order, now)) {
                inserted++;
            }
        }
        return inserted;
    }

    private boolean enqueue(MarketOrder order, Instant now) {
        String requestId = MarketCommand.expireRequestId(order.getId());
        try {
            return Boolean.TRUE.equals(transaction.execute(status -> {
                if (commandRepository.findByUserIdAndRequestId(order.getUserId(), requestId).isPresent()) {
                    return false; // 이미 만료 접수됨(엔진 처리 대기 중이거나 처리됨)
                }
                commandRepository.saveAndFlush(MarketCommand.expire(order.getUserId(), order.getAssetId(), order.getId(), now));
                return true;
            }));
        } catch (DataIntegrityViolationException race) {
            if (UniqueViolations.isViolationOf(race, REQUEST_UNIQUE)) {
                return false; // 다른 실행이 먼저 넣음
            }
            throw race;
        }
    }
}
