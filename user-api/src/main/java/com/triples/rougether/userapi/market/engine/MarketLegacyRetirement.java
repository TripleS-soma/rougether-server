package com.triples.rougether.userapi.market.engine;

import com.triples.rougether.domain.market.entity.*;
import com.triples.rougether.domain.market.repository.*;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 구 주문은 한 건씩 정상 CANCEL을 접수함. 반환 실패 한 건이 다른 주문 처리까지 롤백하지 않음.
@Component
@Slf4j
public class MarketLegacyRetirement {
    private final MarketOrderRepository orders;
    private final MarketCommandRepository commands;
    private final MarketEngineLeaseRepository leases;
    private final Clock clock;
    private final TransactionTemplate transaction;
    @Value("${market.fixed-price.enabled:true}") private boolean fixedPriceEnabled;
    @Value("${market.engine.enabled:false}") private boolean engineEnabled;

    public MarketLegacyRetirement(MarketOrderRepository orders, MarketCommandRepository commands,
            MarketEngineLeaseRepository leases, Clock clock, PlatformTransactionManager manager) {
        this.orders = orders;
        this.commands = commands;
        this.leases = leases;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${market.fixed-price.retire-delay-millis:5000}")
    public void run() {
        if (fixedPriceEnabled && engineEnabled) enqueue();
    }

    public int enqueue() {
        var targets = transaction.execute(s -> orders.findLegacyOrdersToClose(PageRequest.of(0, 200)));
        int count = 0;
        for (MarketOrder order : targets) {
            try {
                boolean inserted = Boolean.TRUE.equals(transaction.execute(status -> {
                    leases.findForUpdate().orElseThrow();
                    String key = "fixed-price-close-" + order.getId();
                    if (commands.findByUserIdAndRequestId(order.getUserId(), key).isPresent()) return false;
                    var current = orders.findById(order.getId()).orElseThrow();
                    if (!current.isOpen()) return false;
                    commands.save(MarketCommand.cancel(order.getUserId(), key, order.getAssetId(), order.getId(), clock.instant()));
                    return true;
                }));
                if (inserted) count++;
            } catch (RuntimeException failure) {
                log.warn("legacy market cancellation enqueue failed: order={}", order.getId(), failure);
            }
        }
        return count;
    }
}
