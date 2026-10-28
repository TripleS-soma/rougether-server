package com.triples.rougether.userapi.market.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob;
import com.triples.rougether.domain.furniture.repository.FurnitureGenerationJobRepository;
import com.triples.rougether.domain.market.entity.CommandStatus;
import com.triples.rougether.domain.market.entity.CommandType;
import com.triples.rougether.domain.market.entity.MarketCommand;
import com.triples.rougether.domain.market.entity.MarketOrder;
import com.triples.rougether.domain.market.entity.MarketTrade;
import com.triples.rougether.domain.market.entity.OrderSide;
import com.triples.rougether.domain.market.entity.OrderSource;
import com.triples.rougether.domain.market.entity.OrderStatus;
import com.triples.rougether.domain.market.repository.MarketAssetRepository;
import com.triples.rougether.domain.market.repository.MarketCommandRepository;
import com.triples.rougether.domain.market.repository.MarketOrderRepository;
import com.triples.rougether.domain.market.repository.MarketTradeRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.market.dto.PlaceOrderRequest;
import com.triples.rougether.userapi.market.error.MarketErrorCode;
import com.triples.rougether.userapi.market.service.MarketAssetService;
import com.triples.rougether.userapi.market.service.MarketOrderCommandService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 매칭 엔진·정산(#401)을 실제 MySQL 에서 검증함. 엔진 스레드는 끄고 runOnce() 로 한 번씩 구동함.
@SpringBootTest(properties = "market.engine.retry-delay-millis=0")
class MarketSaleVisibilityIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @Autowired private MatchingEngine engine;
    @Autowired private com.triples.rougether.userapi.market.service.MarketQueryService query;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean private MarketLedger ledger;
    @Autowired private EngineLeaseManager leaseManager;
    @Autowired private MarketOrderCommandService orders;
    @Autowired private MarketAssetService assetService;
    @Autowired private MarketAssetRepository assets;
    @Autowired private MarketCommandRepository commands;
    @Autowired private MarketOrderRepository marketOrders;
    @Autowired private MarketTradeRepository trades;
    @Autowired private UserRepository users;
    @Autowired private UserWalletRepository wallets;
    @Autowired private ThemeRepository themes;
    @Autowired private ItemRepository items;
    @Autowired private UserItemRepository userItems;
    @Autowired private FurnitureGenerationJobRepository jobs;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private Clock kstClock;

    private User creator;
    private Item photoItem;
    private UserItem creatorCopy;
    private Long assetId;

    @BeforeEach
    void setUp() {
        // 스케줄러 스레드(채팅 등)가 같은 시계 mock 을 부를 수 있어, 마지막 호출에 기대는 when() 대신 doReturn() 으로 스텁함
        doReturn(NOW).when(kstClock).instant();
        doReturn(ZoneId.of("Asia/Seoul")).when(kstClock).getZone();
        creator = newUser(0);
        photoItem = items.save(new Item(themes.findByCode("photo_furniture").orElseThrow(), "furniture", "positioned",
                null, null, "고양이 소파", null, null, "furniture/photo/" + UUID.randomUUID() + ".png", false, false));
        creatorCopy = userItems.save(UserItem.create(creator, photoItem));
        FurnitureGenerationJob job = FurnitureGenerationJob.create(creator.getId(), UUID.randomUUID().toString(),
                "a".repeat(64), "고양이 소파", NOW, NOW.plus(Duration.ofDays(1)));
        job.succeed(photoItem.getAssetKey(), creatorCopy.getId(), NOW);
        jobs.save(job);
        assetId = assetService.issue(creator.getId(), creatorCopy.getId(), 5).assetId();
        // 이 컨텍스트의 인스턴스가 엔진 리스를 가져야 runOnce 가 처리함(다른 테스트 컨텍스트가 남긴 리스는 비움)
        jdbc.update("update market_engine_lease set owner_token = null, lease_until = null where id = 1");
        assertThat(leaseManager.tick()).isEqualTo(EngineLeaseManager.Outcome.TAKEN_OVER);
        engine.invalidateBook();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from market_trades");
        jdbc.update("delete from market_orders");
        jdbc.update("delete from market_commands");
        jdbc.update("delete from market_assets");
        jdbc.update("update market_engine_lease set owner_token = null, lease_until = null where id = 1");
        engine.invalidateBook();
    }

    @Test
    void 발행만으로는_노출하지_않고_내_관리에서_판매를_시작하고_취소할_수_있다() {
        User outsider = newUser(100);
        assertThat(query.listAssets(outsider.getId(), 0, 1).totalElements()).isZero();
        assertThat(query.myAssets(creator.getId(), 0, 1).totalElements()).isEqualTo(1);
        assertThat(query.myAssets(outsider.getId(), 0, 1).items()).isEmpty();
        long command = place(creator, OrderSide.SELL, 50, 1, OrderSource.INVENTORY);
        assertThat(query.myAssets(creator.getId(), 0, 1).items()).hasSize(1);
        engine.runOnce();
        assertThat(query.listAssets(outsider.getId(), 0, 1).totalElements()).isEqualTo(1);
        assertThat(query.getAsset(creator.getId(), assetId).mySellOrders()).singleElement()
                .satisfies(o -> assertThat(o.orderId()).isEqualTo(orderOf(command)));
        assertThat(query.getAsset(outsider.getId(), assetId).mySellOrders()).isEmpty();
        orders.cancel(creator.getId(), orderOf(command), UUID.randomUUID().toString());
        engine.runOnce();
        assertThat(query.listAssets(outsider.getId(), 0, 1).items()).isEmpty();
        assertThat(query.myAssets(creator.getId(), 0, 1).items()).hasSize(1);
        assertThat(activeCopy(creator)).isTrue();
    }

    @Test
    void 판매가_없으면_구매를_접수하거나_코인을_차감하지_않는다() {
        User buyer = newUser(100);
        assertError(() -> place(buyer, OrderSide.BUY, 30, 1, null), MarketErrorCode.NO_SELL_ORDERS);
        assertThat(coin(buyer)).isEqualTo(100);
        assertThat(commands.count()).isZero();
        assertThat(refunds(buyer)).isZero();
    }

    @Test
    void 마지막_판매_취소만_대기와_처리전_구매를_모두_한번_환불한다() {
        User waiting = newUser(100);
        User pending = newUser(100);
        long first = place(creator, OrderSide.SELL, 50, 1, OrderSource.ISSUANCE);
        long last = place(creator, OrderSide.SELL, 60, 1, OrderSource.ISSUANCE);
        engine.runOnce();
        long bid = place(waiting, OrderSide.BUY, 20, 1, null);
        engine.runOnce();
        orders.cancel(creator.getId(), orderOf(first), UUID.randomUUID().toString());
        engine.runOnce();
        assertThat(marketOrders.findById(orderOf(bid)).orElseThrow().isOpen()).isTrue();
        assertThat(coin(waiting)).isEqualTo(80);
        orders.cancel(creator.getId(), orderOf(last), UUID.randomUUID().toString());
        long pendingBuy = place(pending, OrderSide.BUY, 25, 1, null);
        assertThat(coin(pending)).isEqualTo(75);
        engine.runOnce();
        assertThat(marketOrders.findById(orderOf(bid)).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(commands.findById(pendingBuy).orElseThrow().getRejectCode()).isEqualTo("MARKET_NO_SELL_ORDERS");
        assertThat(query.listAssets(waiting.getId(), 0, 20).totalElements()).isZero();
        engine.invalidateBook();
        engine.runOnce();
        for (User buyer : List.of(waiting, pending)) {
            assertThat(coin(buyer)).isEqualTo(100);
            assertThat(refunds(buyer)).isEqualTo(1);
        }
        assertThat(assets.findById(assetId).orElseThrow().getUnissuedQuantity()).isEqualTo(4);
    }

    @Test
    void 마지막_판매_체결은_보유분을_남기고_다른_구매만_환불하며_재판매로_다시_노출된다() {
        User buyer = newUser(100);
        User waiter = newUser(100);
        place(creator, OrderSide.SELL, 50, 1, OrderSource.ISSUANCE);
        engine.runOnce();
        long bid = place(waiter, OrderSide.BUY, 20, 1, null);
        engine.runOnce();
        place(buyer, OrderSide.BUY, 50, 1, null);
        engine.runOnce();
        assertThat(activeCopy(buyer)).isTrue();
        assertThat(coin(buyer)).isEqualTo(50);
        assertThat(coin(waiter)).isEqualTo(100);
        assertThat(marketOrders.findById(orderOf(bid)).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(trades.count()).isEqualTo(1);
        assertThat(query.listAssets(waiter.getId(), 0, 20).items()).isEmpty();
        assertThat(query.myAssets(buyer.getId(), 0, 20).items()).hasSize(1);
        long resale = place(buyer, OrderSide.SELL, 60, 1, OrderSource.INVENTORY);
        assertThat(query.myAssets(buyer.getId(), 0, 20).items()).hasSize(1);
        engine.runOnce();
        assertThat(query.myAssets(buyer.getId(), 0, 20).items()).hasSize(1);
        assertThat(query.getAsset(buyer.getId(), assetId).mySellOrders()).hasSize(1);
        assertThat(query.listAssets(waiter.getId(), 0, 20).items()).hasSize(1);
        assertThat(trades.count()).isEqualTo(1); // 환불된 옛 구매가 재판매와 체결되지 않음
        orders.cancel(buyer.getId(), orderOf(resale), UUID.randomUUID().toString());
        engine.runOnce();
        assertThat(activeCopy(buyer)).isTrue();
    }

    @Test
    void 마지막_판매_만료도_남은_구매를_취소하고_환불한다() {
        User buyer = newUser(100);
        long sell = place(creator, OrderSide.SELL, 50, 1, OrderSource.ISSUANCE);
        engine.runOnce();
        long bid = place(buyer, OrderSide.BUY, 20, 1, null);
        engine.runOnce();
        insertCommand(creator, "expire-" + orderOf(sell), CommandType.EXPIRE, null, null, null, null, orderOf(sell));
        engine.runOnce();
        assertThat(marketOrders.findById(orderOf(sell)).orElseThrow().getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(marketOrders.findById(orderOf(bid)).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(coin(buyer)).isEqualTo(100);
    }

    @Test
    void 환불_도중_실패하면_판매취소도_롤백되고_재처리에서_모두_한번만_환불한다() {
        User first = newUser(100);
        User second = newUser(100);
        long sale = place(creator, OrderSide.SELL, 50, 1, OrderSource.ISSUANCE);
        engine.runOnce();
        long firstBid = place(first, OrderSide.BUY, 20, 1, null);
        long secondBid = place(second, OrderSide.BUY, 25, 1, null);
        engine.runOnce();
        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(call -> {
            call.callRealMethod();
            MarketOrder order = call.getArgument(0);
            if (order.getUserId().equals(second.getId()) && failed.compareAndSet(false, true))
                throw new IllegalStateException("rollback after second refund");
            return null;
        }).when(ledger).refundOrder(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        orders.cancel(creator.getId(), orderOf(sale), UUID.randomUUID().toString());
        assertThat(engine.runOnce()).isEqualTo(-1);
        for (long id : List.of(sale, firstBid, secondBid))
            assertThat(marketOrders.findById(orderOf(id)).orElseThrow().isOpen()).isTrue();
        assertThat(coin(first)).isEqualTo(80);
        assertThat(coin(second)).isEqualTo(75);
        assertThat(refunds(first)).isZero();
        engine.runOnce();
        engine.invalidateBook();
        engine.runOnce();
        for (User buyer : List.of(first, second)) {
            assertThat(coin(buyer)).isEqualTo(100);
            assertThat(refunds(buyer)).isEqualTo(1);
        }
    }

    @Test
    void 예전_정책의_판매없는_구매는_새_접수_없이도_복구하고_재실행해도_중복환불이_없다() {
        User buyer = newUser(100);
        long sale = place(creator, OrderSide.SELL, 50, 1, OrderSource.ISSUANCE);
        engine.runOnce();
        long bid = place(buyer, OrderSide.BUY, 20, 1, null);
        engine.runOnce();
        // 이전 정책에서 판매만 종료되고 구매가 남은 운영 데이터를 재현한다.
        jdbc.update("update market_orders set status = 'CANCELLED', escrow_remaining = 0 where id = ?", orderOf(sale));
        engine.invalidateBook();
        engine.runOnce();
        engine.runOnce();
        assertThat(coin(buyer)).isEqualTo(100);
        assertThat(refunds(buyer)).isEqualTo(1);
        assertThat(marketOrders.findById(orderOf(bid)).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(commands.findAll()).anySatisfy(c -> {
            assertThat(c.getType()).isEqualTo(CommandType.CANCEL);
            assertThat(c.getTargetOrderId()).isEqualTo(orderOf(bid));
            assertThat(c.getEngineSeq()).isNotNull();
        });
    }

    @Test
    void 판매취소와_동시_구매는_접수거절하거나_정확히_환불한다() throws Exception {
        User buyer = newUser(100);
        long sale = place(creator, OrderSide.SELL, 50, 1, OrderSource.ISSUANCE);
        engine.runOnce();
        orders.cancel(creator.getId(), orderOf(sale), UUID.randomUUID().toString());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<?> engineTask = executor.submit(() -> { start.await(); return engine.runOnce(); });
            Future<Long> buyTask = executor.submit(() -> {
                start.await();
                try { return place(buyer, OrderSide.BUY, 20, 1, null); }
                catch (BusinessException ex) {
                    assertThat(ex.getErrorCode()).isEqualTo(MarketErrorCode.NO_SELL_ORDERS);
                    return null;
                }
            });
            start.countDown();
            engineTask.get(10, java.util.concurrent.TimeUnit.SECONDS);
            Long admitted = buyTask.get(10, java.util.concurrent.TimeUnit.SECONDS);
            engine.runOnce();
            assertThat(coin(buyer)).isEqualTo(100);
            assertThat(refunds(buyer)).isEqualTo(admitted == null ? 0 : 1);
            if (admitted != null) assertThat(commands.findById(admitted).orElseThrow().getRejectCode()).isEqualTo("MARKET_NO_SELL_ORDERS");
            assertThat(marketOrders.findAll()).noneMatch(MarketOrder::isOpen);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void 자동취소_후_재판매까지_접수_대장을_재생하면_실제_체결과_같다() {
        User waiter = newUser(100);
        User firstBuyer = newUser(100);
        User nextBuyer = newUser(100);
        place(creator, OrderSide.SELL, 50, 1, OrderSource.ISSUANCE);
        engine.runOnce();
        place(waiter, OrderSide.BUY, 20, 1, null);
        engine.runOnce();
        place(firstBuyer, OrderSide.BUY, 50, 1, null);
        engine.runOnce(); // waiter의 구매가 자동 취소됨
        place(firstBuyer, OrderSide.SELL, 10, 1, OrderSource.INVENTORY);
        engine.runOnce(); // 옛 20코인 구매와 체결되면 안 됨
        place(nextBuyer, OrderSide.BUY, 10, 1, null);
        engine.runOnce();

        OrderBook replay = new OrderBook();
        List<OrderBook.Fill> fills = new ArrayList<>();
        for (MarketCommand command : commands.findByEngineSeqIsNotNullOrderByEngineSeqAsc()) {
            if (command.getStatus() != CommandStatus.APPLIED) continue;
            if (command.getType() == CommandType.PLACE) {
                fills.addAll(replay.match(command.getAssetId(), command.getOrderId(), command.getUserId(),
                        command.getSide(), command.getPrice(), command.getQuantity()));
            } else {
                replay.remove(command.getAssetId(), command.getTargetOrderId());
            }
        }
        assertThat(trades.findAll()).extracting(MarketTrade::getPrice).containsExactly(50, 10);
        assertThat(fills).extracting(OrderBook.Fill::price).containsExactly(50, 10);
        assertThat(coin(waiter)).isEqualTo(100);
        assertThat(activeCopy(waiter)).isFalse();
        assertThat(activeCopy(nextBuyer)).isTrue();
    }

    private long refunds(User user) {
        return jdbc.queryForObject("select count(*) from wallet_histories where user_id = ? and reason = 'MARKET_ESCROW_REFUND'", Long.class, user.getId());
    }

    private long place(User user, OrderSide side, int price, int quantity, OrderSource source) {
        return orders.place(user.getId(), new PlaceOrderRequest(UUID.randomUUID().toString(), assetId, side, price,
                quantity, source)).commandId();
    }

    private long orderOf(long commandId) {
        return commands.findById(commandId).orElseThrow().getOrderId();
    }

    private long insertCommand(User user, String requestId, CommandType type, OrderSide side, OrderSource source,
                               Integer price, Integer escrow, Long targetOrderId) {
        jdbc.update("insert into market_commands (user_id, request_id, type, asset_id, side, source, price, quantity, "
                        + "escrow_amount, target_order_id, status, attempts, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?)",
                user.getId(), requestId, type.name(), assetId, side == null ? null : side.name(),
                source == null ? null : source.name(), price, price == null ? null : 1, escrow, targetOrderId,
                Timestamp.from(NOW));
        return jdbc.queryForObject("select id from market_commands where user_id = ? and request_id = ?", Long.class,
                user.getId(), requestId);
    }

    private User newUser(int coin) {
        User user = users.save(User.signUp("market-engine-" + UUID.randomUUID() + "@example.test"));
        wallets.save(UserWallet.createWithBalance(user, CurrencyType.COIN, coin));
        return user;
    }

    private int coin(User user) {
        return wallets.findByUserIdAndCurrencyType(user.getId(), CurrencyType.COIN).orElseThrow().getBalance();
    }

    private boolean activeCopy(User user) {
        return userItems.existsByUserIdAndItemIdAndDeletedAtIsNull(user.getId(), photoItem.getId());
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, MarketErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
