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
class MatchingEngineIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @Autowired private MatchingEngine engine;
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
        engine.invalidateBook();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from market_trades");
        jdbc.update("delete from market_orders");
        jdbc.update("delete from market_commands");
        jdbc.update("delete from market_assets");
        engine.invalidateBook();
    }

    @Test
    void 발행_재고를_사면_먼저_걸린_가격으로_체결되고_차액을_돌려받는다() {
        User buyer = newUser(100);
        place(creator, OrderSide.SELL, 30, 2, OrderSource.ISSUANCE);
        place(buyer, OrderSide.BUY, 35, 1, null);

        assertThat(engine.runOnce()).isEqualTo(2);

        MarketTrade trade = single(trades.findAll());
        assertThat(trade.getPrice()).isEqualTo(30);
        assertThat(trade.getRoyaltyAmount()).isZero(); // 제작자 본인 판매
        assertThat(trade.getFeeAmount()).isEqualTo(1); // 30 × 5% = 1.5 → 1
        assertThat(coin(buyer)).isEqualTo(70); // 35 맡기고 5 돌려받음
        assertThat(coin(creator)).isEqualTo(29);
        assertThat(activeCopy(buyer)).isTrue();
        MarketOrder sell = marketOrders.findById(trade.getSellOrderId()).orElseThrow();
        assertThat(sell.getStatus()).isEqualTo(OrderStatus.OPEN);
        assertThat(sell.remaining()).isEqualTo(1);
        assertThat(marketOrders.findById(trade.getBuyOrderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(commands.findAll()).allSatisfy(c -> {
            assertThat(c.getStatus()).isEqualTo(CommandStatus.APPLIED);
            assertThat(c.getEngineSeq()).isNotNull();
        });
    }

    @Test
    void 되팔면_제작자에게_로열티가_가고_수수료만큼_코인이_사라진다() {
        User reseller = newUser(100);
        User buyer = newUser(100);
        place(creator, OrderSide.SELL, 30, 1, OrderSource.ISSUANCE);
        place(reseller, OrderSide.BUY, 30, 1, null);
        engine.runOnce();
        int before = coin(creator) + coin(reseller) + coin(buyer);

        place(reseller, OrderSide.SELL, 40, 1, OrderSource.INVENTORY);
        place(buyer, OrderSide.BUY, 40, 1, null);
        engine.runOnce();

        MarketTrade resale = trades.findAll().stream().filter(t -> t.getPrice() == 40).findFirst().orElseThrow();
        assertThat(resale.getRoyaltyUserId()).isEqualTo(creator.getId());
        assertThat(resale.getRoyaltyAmount()).isEqualTo(4);
        assertThat(resale.getFeeAmount()).isEqualTo(2);
        assertThat(coin(reseller)).isEqualTo(70 + 34);
        assertThat(coin(creator)).isEqualTo(29 + 4);
        assertThat(coin(creator) + coin(reseller) + coin(buyer)).isEqualTo(before - resale.getFeeAmount());
        assertThat(activeCopy(buyer)).isTrue();
        assertThat(activeCopy(reseller)).isFalse();
    }

    @Test
    void 제작자가_탈퇴해_비워지면_로열티는_없다() {
        User reseller = newUser(100);
        User buyer = newUser(100);
        place(creator, OrderSide.SELL, 30, 1, OrderSource.ISSUANCE);
        place(reseller, OrderSide.BUY, 30, 1, null);
        engine.runOnce();
        jdbc.update("update market_assets set creator_user_id = null where id = ?", assetId);

        place(reseller, OrderSide.SELL, 40, 1, OrderSource.INVENTORY);
        place(buyer, OrderSide.BUY, 40, 1, null);
        engine.runOnce();

        MarketTrade resale = trades.findAll().stream().filter(t -> t.getPrice() == 40).findFirst().orElseThrow();
        assertThat(resale.getRoyaltyUserId()).isNull();
        assertThat(resale.getRoyaltyAmount()).isZero();
        assertThat(coin(reseller)).isEqualTo(70 + 38);
    }

    @Test
    void 취소하면_맡긴_코인_보유분_발행_재고를_돌려받는다() {
        User buyer = newUser(100);
        long buyCommand = place(buyer, OrderSide.BUY, 20, 1, null);
        long issuanceCommand = place(creator, OrderSide.SELL, 50, 2, OrderSource.ISSUANCE);
        long inventoryCommand = place(creator, OrderSide.SELL, 60, 1, OrderSource.INVENTORY);
        engine.runOnce();
        assertThat(coin(buyer)).isEqualTo(80);
        assertThat(assets.findById(assetId).orElseThrow().getUnissuedQuantity()).isEqualTo(2);
        assertThat(activeCopy(creator)).isFalse();

        orders.cancel(buyer.getId(), orderOf(buyCommand), UUID.randomUUID().toString());
        orders.cancel(creator.getId(), orderOf(issuanceCommand), UUID.randomUUID().toString());
        orders.cancel(creator.getId(), orderOf(inventoryCommand), UUID.randomUUID().toString());
        engine.runOnce();

        assertThat(coin(buyer)).isEqualTo(100);
        assertThat(assets.findById(assetId).orElseThrow().getUnissuedQuantity()).isEqualTo(4);
        assertThat(activeCopy(creator)).isTrue();
        assertThat(marketOrders.findAll()).allSatisfy(o -> {
            assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(o.getEscrowRemaining()).isZero();
        });
        assertThat(jdbc.queryForObject("select count(*) from wallet_histories where user_id = ? "
                + "and reason = 'MARKET_ESCROW_REFUND' and source_type = 'MARKET_ORDER'", Long.class, buyer.getId())).isEqualTo(1);
    }

    @Test
    void 만료_접수는_주문을_만료시키고_돌려준다() {
        User buyer = newUser(100);
        long buyCommand = place(buyer, OrderSide.BUY, 20, 1, null);
        engine.runOnce();
        long order = orderOf(buyCommand);
        MarketOrder opened = marketOrders.findById(order).orElseThrow();
        assertThat(opened.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));

        insertCommand(buyer, "expire-" + order, CommandType.EXPIRE, null, null, null, null, order);
        engine.runOnce();

        assertThat(marketOrders.findById(order).orElseThrow().getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(coin(buyer)).isEqualTo(100);
    }

    @Test
    void 같은_주문을_두_번_취소해도_환불은_한_번이다() {
        User buyer = newUser(100);
        long buyCommand = place(buyer, OrderSide.BUY, 20, 1, null);
        engine.runOnce();
        long order = orderOf(buyCommand);

        long first = orders.cancel(buyer.getId(), order, UUID.randomUUID().toString()).commandId();
        long second = orders.cancel(buyer.getId(), order, UUID.randomUUID().toString()).commandId();
        engine.runOnce();

        assertThat(commands.findById(first).orElseThrow().getStatus()).isEqualTo(CommandStatus.APPLIED);
        MarketCommand rejected = commands.findById(second).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(CommandStatus.REJECTED);
        assertThat(rejected.getRejectCode()).isEqualTo("MARKET_ORDER_NOT_OPEN");
        assertThat(coin(buyer)).isEqualTo(100);
    }

    @Test
    void 거래가_정지되면_엔진이_새_주문을_거절하고_돌려준다() {
        User buyer = newUser(100);
        long command = place(buyer, OrderSide.BUY, 30, 1, null);
        jdbc.update("update market_assets set status = 'SUSPENDED' where id = ?", assetId);

        engine.runOnce();

        MarketCommand rejected = commands.findById(command).orElseThrow();
        assertThat(rejected.getRejectCode()).isEqualTo("MARKET_ASSET_SUSPENDED");
        assertThat(coin(buyer)).isEqualTo(100);
        assertThat(marketOrders.count()).isZero();
    }

    @Test
    void 자기_주문과_맞물리는_접수는_체결_전에_거절하고_돌려준다() {
        // 주문 API 가 막는 경우라 접수를 직접 넣어 엔진 방어선만 확인함
        place(creator, OrderSide.SELL, 30, 1, OrderSource.ISSUANCE);
        engine.runOnce();
        long command = insertCommand(creator, UUID.randomUUID().toString(), CommandType.PLACE, OrderSide.BUY, null, 30, 30, null);

        engine.runOnce();

        assertThat(commands.findById(command).orElseThrow().getRejectCode()).isEqualTo("MARKET_SELF_TRADE");
        assertThat(coin(creator)).isEqualTo(30); // 맡긴 것으로 기록된 30 을 돌려받음(접수 원장 source)
        assertThat(trades.count()).isZero();
    }

    @Test
    void 처리된_접수를_순서대로_재생하면_같은_체결이_나온다() {
        List<User> buyers = List.of(newUser(200), newUser(200), newUser(200), newUser(200));
        place(creator, OrderSide.SELL, 30, 2, OrderSource.ISSUANCE);
        place(buyers.get(0), OrderSide.BUY, 25, 1, null);
        place(buyers.get(1), OrderSide.BUY, 31, 1, null);
        engine.runOnce();
        place(creator, OrderSide.SELL, 24, 1, OrderSource.ISSUANCE);
        place(buyers.get(2), OrderSide.BUY, 40, 1, null);
        place(buyers.get(3), OrderSide.BUY, 30, 1, null);
        engine.runOnce();
        long cancelTarget = orderOf(commands.findAll().stream()
                .filter(c -> c.getUserId().equals(buyers.get(3).getId())).findFirst().orElseThrow().getId());
        if (marketOrders.findById(cancelTarget).orElseThrow().isOpen()) {
            orders.cancel(buyers.get(3).getId(), cancelTarget, UUID.randomUUID().toString());
            engine.runOnce();
        }

        OrderBook replay = new OrderBook();
        List<OrderBook.Fill> replayed = new ArrayList<>();
        for (MarketCommand command : commands.findByEngineSeqIsNotNullOrderByEngineSeqAsc()) {
            if (command.getStatus() != CommandStatus.APPLIED) {
                continue;
            }
            if (command.getType() == CommandType.PLACE) {
                replayed.addAll(replay.match(command.getAssetId(), command.getOrderId(), command.getUserId(),
                        command.getSide(), command.getPrice(), command.getQuantity()));
            } else {
                replay.remove(command.getAssetId(), command.getTargetOrderId());
            }
        }

        List<MarketTrade> recorded = trades.findAll().stream()
                .sorted(java.util.Comparator.comparing(MarketTrade::getId)).toList();
        assertThat(recorded).isNotEmpty();
        assertThat(replayed).hasSameSizeAs(recorded);
        for (int i = 0; i < recorded.size(); i++) {
            MarketTrade trade = recorded.get(i);
            long maker = trade.getBuyOrderId() < trade.getSellOrderId() ? trade.getBuyOrderId() : trade.getSellOrderId();
            assertThat(replayed.get(i)).isEqualTo(new OrderBook.Fill(maker, trade.getPrice(), trade.getQuantity()));
        }
    }

    @Test
    void 매수가_동시에_몰려도_매물_수보다_많이_체결되지_않고_1인_1개가_유지된다() throws Exception {
        place(creator, OrderSide.SELL, 30, 3, OrderSource.ISSUANCE);
        engine.runOnce();
        List<User> buyers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            buyers.add(newUser(100));
        }

        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (User buyer : buyers) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return place(buyer, OrderSide.BUY, 30, 1, null);
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
        engine.runOnce();

        assertThat(trades.count()).isEqualTo(3);
        assertThat(buyers.stream().filter(this::activeCopy).count()).isEqualTo(3);
        for (User buyer : buyers) {
            assertThat(userItems.findByUserIdAndDeletedAtIsNull(buyer.getId()).stream()
                    .filter(ui -> ui.getItem().getId().equals(photoItem.getId())).count()).isLessThanOrEqualTo(1);
        }
        assertThat(marketOrders.findAll().stream()
                .filter(o -> o.getSide() == OrderSide.BUY && o.isOpen()).count()).isEqualTo(3);
    }

    @Test
    void 계속_실패하는_접수는_세_번_뒤_거절되고_다음_접수가_처리된다() {
        User poisoned = newUser(100);
        User next = newUser(100);
        place(creator, OrderSide.SELL, 30, 1, OrderSource.ISSUANCE);
        engine.runOnce();
        long poisonedCommand = place(poisoned, OrderSide.BUY, 30, 1, null);
        long nextCommand = place(next, OrderSide.BUY, 30, 1, null);
        // 체결 시 구매자 user_item 을 만들 수 없게(활성 유니크 위반) 미리 활성 사본을 넣음
        userItems.save(UserItem.create(poisoned, photoItem));

        assertThat(engine.runOnce()).isEqualTo(-1);
        assertThat(engine.runOnce()).isEqualTo(-1);
        assertThat(engine.runOnce()).isEqualTo(-1);
        MarketCommand rejected = commands.findById(poisonedCommand).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(CommandStatus.REJECTED);
        assertThat(rejected.getRejectCode()).isEqualTo("MARKET_ENGINE_ERROR");
        assertThat(rejected.getAttempts()).isEqualTo(3);
        assertThat(coin(poisoned)).isEqualTo(100);

        engine.runOnce();

        assertThat(commands.findById(nextCommand).orElseThrow().getStatus()).isEqualTo(CommandStatus.APPLIED);
        assertThat(activeCopy(next)).isTrue();
    }

    @Test
    void 반복_실패_후_환불까지_실패하면_수동_대사_코드로_남긴다() {
        User buyer = newUser(100);
        place(buyer, OrderSide.BUY, 30, 1, null);
        engine.runOnce();
        long sellCommand = place(creator, OrderSide.SELL, 30, 1, OrderSource.INVENTORY);
        // 체결 실패: 구매자가 이미 활성 사본을 가짐 / 환불 실패: 판매자가 맡긴 사본을 되살릴 수 없게 다른 활성 사본을 가짐
        userItems.save(UserItem.create(buyer, photoItem));
        userItems.save(UserItem.create(creator, photoItem));

        engine.runOnce();
        engine.runOnce();
        engine.runOnce();

        MarketCommand rejected = commands.findById(sellCommand).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(CommandStatus.REJECTED);
        assertThat(rejected.getRejectCode()).isEqualTo("MARKET_ENGINE_ERROR_UNREFUNDED");
        assertThat(userItems.findById(creatorCopy.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(trades.count()).isZero();
    }

    @Test
    void 같은_가구에_내_판매가_있으면_살_수_없고_내_구매가_있으면_발행_재고를_팔_수_없다() {
        User buyer = newUser(100);
        place(creator, OrderSide.SELL, 30, 1, OrderSource.INVENTORY);
        place(buyer, OrderSide.BUY, 30, 1, null);
        engine.runOnce(); // 제작자는 이제 보유분이 없음
        place(creator, OrderSide.SELL, 50, 1, OrderSource.ISSUANCE);

        assertError(() -> place(creator, OrderSide.BUY, 50, 1, null), MarketErrorCode.OWN_ORDER_CONFLICT); // 처리 전 접수
        engine.runOnce();
        assertError(() -> place(creator, OrderSide.BUY, 50, 1, null), MarketErrorCode.OWN_ORDER_CONFLICT); // 대기 주문

        long issuanceOrder = orderOf(commands.findAll().stream()
                .filter(c -> c.getSource() == OrderSource.ISSUANCE).findFirst().orElseThrow().getId());
        orders.cancel(creator.getId(), issuanceOrder, UUID.randomUUID().toString());
        engine.runOnce();
        jdbc.update("update user_wallets set balance = 100 where user_id = ? and currency_type = 'COIN'", creator.getId());
        place(creator, OrderSide.BUY, 10, 1, null);

        assertError(() -> place(creator, OrderSide.SELL, 50, 1, OrderSource.ISSUANCE), MarketErrorCode.OWN_ORDER_CONFLICT);
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

    private static <T> T single(List<T> list) {
        assertThat(list).hasSize(1);
        return list.get(0);
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, MarketErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
