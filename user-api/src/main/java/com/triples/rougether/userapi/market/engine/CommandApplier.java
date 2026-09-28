package com.triples.rougether.userapi.market.engine;

import com.triples.rougether.domain.market.entity.CommandStatus;
import com.triples.rougether.domain.market.entity.CommandType;
import com.triples.rougether.domain.market.entity.MarketAsset;
import com.triples.rougether.domain.market.entity.MarketCommand;
import com.triples.rougether.domain.market.entity.MarketEngineLease;
import com.triples.rougether.domain.market.entity.MarketOrder;
import com.triples.rougether.domain.market.entity.OrderSource;
import com.triples.rougether.domain.market.entity.OrderStatus;
import com.triples.rougether.domain.market.repository.MarketAssetRepository;
import com.triples.rougether.domain.market.repository.MarketCommandRepository;
import com.triples.rougether.domain.market.repository.MarketEngineLeaseRepository;
import com.triples.rougether.domain.market.repository.MarketOrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 접수 1건 = 트랜잭션 1개로 처리함(#401). 도장(engine_seq) → 처리 → APPLIED/REJECTED 가 함께 커밋되거나 함께 롤백됨.
// 리스 행을 첫 잠금으로 잡아 순번 부여를 직렬화하고, 같은 자리에서 펜싱 번호를 확인함(#402).
// 잠금을 쥔 채 확인하므로 확인한 번호가 커밋 순간까지 유효함(인수는 이 트랜잭션이 끝날 때까지 기다림).
// 발행 재고를 되돌릴 수 있는 경로는 종목 행을 처음부터 잠금 조회함(일반 조회 선행 시 갱신 유실).
@Component
public class CommandApplier {

    static final String SELF_TRADE = "MARKET_SELF_TRADE";
    static final String ASSET_SUSPENDED = "MARKET_ASSET_SUSPENDED";
    static final String ORDER_NOT_OPEN = "MARKET_ORDER_NOT_OPEN";
    static final String ENGINE_ERROR = "MARKET_ENGINE_ERROR";
    // 반복 실패 거절 중 환불까지 실패한 접수. 수동 대사 대상(reject_code 로 조회).
    static final String ENGINE_ERROR_UNREFUNDED = "MARKET_ENGINE_ERROR_UNREFUNDED";
    static final int REFUND_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(CommandApplier.class);

    private final MarketEngineLeaseRepository leaseRepository;
    private final MarketCommandRepository commandRepository;
    private final MarketOrderRepository orderRepository;
    private final MarketAssetRepository assetRepository;
    private final TradeSettler settler;
    private final MarketLedger ledger;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;

    public CommandApplier(MarketEngineLeaseRepository leaseRepository, MarketCommandRepository commandRepository,
                          MarketOrderRepository orderRepository, MarketAssetRepository assetRepository,
                          TradeSettler settler, MarketLedger ledger, Clock clock,
                          PlatformTransactionManager transactionManager) {
        this.leaseRepository = leaseRepository;
        this.commandRepository = commandRepository;
        this.orderRepository = orderRepository;
        this.assetRepository = assetRepository;
        this.settler = settler;
        this.ledger = ledger;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    // 호가창(book)은 이 트랜잭션 안에서 바뀌므로, 호출 측은 예외가 나면 호가창을 DB 에서 다시 적재해야 함.
    public void apply(Long commandId, OrderBook book, long fencingToken) {
        transaction.executeWithoutResult(status -> {
            MarketEngineLease lease = lockLease(fencingToken);
            MarketCommand command = commandRepository.findById(commandId).orElseThrow();
            if (!command.isPending()) {
                return;
            }
            long seq = lease.nextSeq();
            command.stamp(seq);
            Instant now = clock.instant();
            if (command.getType() == CommandType.PLACE) {
                place(command, seq, book, now);
            } else {
                close(command, book, now);
            }
        });
    }

    // 처리 실패 횟수를 별도 트랜잭션에서 올림(처리 트랜잭션은 롤백됐으므로).
    // 펜싱도 확인함: 이미 인수당한 옛 엔진이 횟수를 올려 새 엔진이 일찍 거절하는 일을 막음.
    public int recordFailure(Long commandId, long fencingToken) {
        return transaction.execute(status -> {
            lockLease(fencingToken);
            commandRepository.incrementAttempts(commandId);
            return commandRepository.findById(commandId).orElseThrow().getAttempts();
        });
    }

    // 반복 실패한 접수를 거절하고 맡긴 것을 돌려줌. 환불은 일시 장애일 수 있어 몇 번 더 시도하고,
    // 그래도 실패하면 MARKET_ENGINE_ERROR_UNREFUNDED 로 남겨 수동 대사 대상임을 DB 에서 구분할 수 있게 함.
    public void rejectAsEngineError(Long commandId, long fencingToken) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= REFUND_ATTEMPTS; attempt++) {
            try {
                transaction.executeWithoutResult(status -> reject(commandId, true, fencingToken));
                return;
            } catch (FencedOutException fenced) {
                throw fenced;
            } catch (RuntimeException refundFailed) {
                last = refundFailed;
            }
        }
        log.error("market command {} rejected without refund; manual reconciliation needed", commandId, last);
        transaction.executeWithoutResult(status -> reject(commandId, false, fencingToken));
    }

    public List<Long> pendingIds(int limit) {
        return readOnly.execute(status -> commandRepository
                .findByStatusOrderByIdAsc(CommandStatus.PENDING, PageRequest.of(0, limit))
                .stream().map(MarketCommand::getId).toList());
    }

    // DB 의 OPEN 주문이 정본. 처리 순서대로 넣어 같은 가격 안의 순서를 복원함.
    public void reload(OrderBook book) {
        readOnly.executeWithoutResult(status -> {
            book.clear();
            for (MarketOrder order : orderRepository.findByStatusOrderByEngineSeqAsc(OrderStatus.OPEN)) {
                book.rest(order.getAssetId(), order.getId(), order.getUserId(), order.getSide(), order.getPrice(),
                        order.remaining());
            }
        });
    }

    private void place(MarketCommand command, long seq, OrderBook book, Instant now) {
        MarketAsset asset = loadAsset(command.getAssetId(), command.getSource() == OrderSource.ISSUANCE);
        if (asset.isSuspended()) {
            ledger.refundCommand(command, asset, now);
            command.rejected(ASSET_SUSPENDED, now);
            return;
        }
        if (book.crossesOwn(asset.getId(), command.getUserId(), command.getSide(), command.getPrice())) {
            ledger.refundCommand(command, asset, now);
            command.rejected(SELF_TRADE, now);
            return;
        }
        MarketOrder taker = orderRepository.save(MarketOrder.open(command, seq, now));
        List<OrderBook.Fill> fills = book.match(asset.getId(), taker.getId(), taker.getUserId(), taker.getSide(),
                taker.getPrice(), taker.getQuantity());
        for (OrderBook.Fill fill : fills) {
            MarketOrder maker = orderRepository.findById(fill.makerOrderId()).orElseThrow();
            settler.settle(asset, taker, maker, fill.price(), fill.quantity(), seq, now);
        }
        command.applied(taker.getId(), now);
    }

    private void close(MarketCommand command, OrderBook book, Instant now) {
        MarketOrder order = orderRepository.findById(command.getTargetOrderId()).orElse(null);
        if (order == null || !order.isOpen() || !order.getUserId().equals(command.getUserId())) {
            command.rejected(ORDER_NOT_OPEN, now);
            return;
        }
        MarketAsset asset = order.getSource() == OrderSource.ISSUANCE ? loadAsset(order.getAssetId(), true) : null;
        ledger.refundOrder(order, asset, now);
        order.close(command.getType() == CommandType.EXPIRE ? OrderStatus.EXPIRED : OrderStatus.CANCELLED, now);
        book.remove(order.getAssetId(), order.getId());
        command.applied(null, now);
    }

    private void reject(Long commandId, boolean refund, long fencingToken) {
        MarketEngineLease lease = lockLease(fencingToken);
        MarketCommand command = commandRepository.findById(commandId).orElseThrow();
        if (!command.isPending()) {
            return;
        }
        command.stamp(lease.nextSeq());
        Instant now = clock.instant();
        if (refund && command.getType() == CommandType.PLACE) {
            MarketAsset asset = command.getSource() == OrderSource.ISSUANCE
                    ? loadAsset(command.getAssetId(), true) : null;
            ledger.refundCommand(command, asset, now);
        }
        command.rejected(refund ? ENGINE_ERROR : ENGINE_ERROR_UNREFUNDED, now);
    }

    // 리스 행 잠금 + 펜싱 확인. 토큰은 기본형 long 으로 비교함(Long 참조 비교 금지).
    private MarketEngineLease lockLease(long fencingToken) {
        MarketEngineLease lease = leaseRepository.findForUpdate().orElseThrow();
        if (lease.getFencingToken() != fencingToken) {
            throw new FencedOutException(fencingToken, lease.getFencingToken());
        }
        return lease;
    }

    private MarketAsset loadAsset(Long assetId, boolean lock) {
        return (lock ? assetRepository.findWithLockById(assetId) : assetRepository.findById(assetId)).orElseThrow();
    }
}
