package com.triples.rougether.userapi.market.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob;
import com.triples.rougether.domain.furniture.repository.FurnitureGenerationJobRepository;
import com.triples.rougether.domain.market.entity.CommandStatus;
import com.triples.rougether.domain.market.entity.CommandType;
import com.triples.rougether.domain.market.entity.MarketCommand;
import com.triples.rougether.domain.market.entity.OrderSide;
import com.triples.rougether.domain.market.entity.OrderSource;
import com.triples.rougether.domain.market.entity.OrderStatus;
import com.triples.rougether.domain.market.repository.MarketAssetRepository;
import com.triples.rougether.domain.market.repository.MarketCommandRepository;
import com.triples.rougether.domain.market.repository.MarketOrderRepository;
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
import com.triples.rougether.userapi.market.service.MarketAssetService;
import com.triples.rougether.userapi.market.service.MarketOrderCommandService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

// 7일 만료(#403)를 실제 MySQL 에서 검증함. 스케줄러는 EXPIRE 접수만 넣고 엔진(runOnce)이 처리함.
@SpringBootTest(properties = "market.engine.retry-delay-millis=0")
class MarketOrderExpiryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final Instant EXPIRY = NOW.plus(Duration.ofDays(7));

    @Autowired private MarketOrderExpiryScheduler expiry;
    @Autowired private MatchingEngine engine;
    @Autowired private EngineLeaseManager leaseManager;
    @Autowired private MarketOrderCommandService orders;
    @Autowired private MarketAssetService assetService;
    @Autowired private MarketAssetRepository assets;
    @Autowired private MarketCommandRepository commands;
    @Autowired private MarketOrderRepository marketOrders;
    @Autowired private UserRepository users;
    @Autowired private UserWalletRepository wallets;
    @Autowired private ThemeRepository themes;
    @Autowired private ItemRepository items;
    @Autowired private UserItemRepository userItems;
    @Autowired private FurnitureGenerationJobRepository jobs;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    // 스케줄러 스레드가 같은 시계 mock 을 부를 수 있어 when() 대신 doReturn() 으로 스텁함
    @MockitoBean private Clock kstClock;

    private User creator;
    private Item photoItem;
    private Long assetId;

    @BeforeEach
    void setUp() {
        at(NOW);
        doReturn(ZoneId.of("Asia/Seoul")).when(kstClock).getZone();
        creator = newUser(0);
        photoItem = items.save(new Item(themes.findByCode("photo_furniture").orElseThrow(), "furniture", "positioned",
                null, null, "고양이 소파", null, null, "furniture/photo/" + UUID.randomUUID() + ".png", false, false));
        UserItem creatorCopy = userItems.save(UserItem.create(creator, photoItem));
        FurnitureGenerationJob job = FurnitureGenerationJob.create(creator.getId(), UUID.randomUUID().toString(),
                "a".repeat(64), "고양이 소파", NOW, NOW.plus(Duration.ofDays(1)));
        job.succeed(photoItem.getAssetKey(), creatorCopy.getId(), NOW);
        jobs.save(job);
        assetId = assetService.issue(creator.getId(), creatorCopy.getId(), 5).assetId();
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
    void 칠일이_지난_매수_보유분_매도_발행_재고_매도가_만료되고_맡긴_것을_돌려받는다() {
        User buyer = newUser(100);
        place(buyer, OrderSide.BUY, 20, 1, null);
        place(creator, OrderSide.SELL, 50, 2, OrderSource.ISSUANCE);
        place(creator, OrderSide.SELL, 60, 1, OrderSource.INVENTORY);
        engine.runOnce();
        assertThat(coin(buyer)).isEqualTo(80);
        assertThat(assets.findById(assetId).orElseThrow().getUnissuedQuantity()).isEqualTo(2);
        assertThat(activeCopy(creator)).isFalse();

        at(EXPIRY);
        assertThat(expiry.expireDue()).isEqualTo(3);
        engine.runOnce();

        assertThat(marketOrders.findAll()).allSatisfy(o -> {
            assertThat(o.getStatus()).isEqualTo(OrderStatus.EXPIRED);
            assertThat(o.getEscrowRemaining()).isZero();
        });
        assertThat(coin(buyer)).isEqualTo(100);
        assertThat(assets.findById(assetId).orElseThrow().getUnissuedQuantity()).isEqualTo(4);
        assertThat(activeCopy(creator)).isTrue();
    }

    @Test
    void 아직_칠일이_안_된_주문은_건드리지_않는다() {
        place(newUser(100), OrderSide.BUY, 20, 1, null);
        engine.runOnce();

        at(EXPIRY.minusSeconds(1));

        assertThat(expiry.expireDue()).isZero();
        assertThat(marketOrders.findAll()).allSatisfy(o -> assertThat(o.getStatus()).isEqualTo(OrderStatus.OPEN));
    }

    @Test
    void 여러_번_돌아도_주문당_만료_접수는_한_번이다() {
        place(newUser(100), OrderSide.BUY, 20, 1, null);
        place(creator, OrderSide.SELL, 50, 1, OrderSource.ISSUANCE);
        engine.runOnce();
        at(EXPIRY);

        assertThat(expiry.expireDue()).isEqualTo(2);
        assertThat(expiry.expireDue()).isZero(); // 엔진 처리 전
        engine.runOnce();
        assertThat(expiry.expireDue()).isZero(); // 엔진 처리 후

        assertThat(commands.findAll().stream().filter(c -> c.getType() == CommandType.EXPIRE).count()).isEqualTo(2);
    }

    @Test
    void 만료_직전에_체결된_주문은_만료_접수가_거절되고_상태가_바뀌지_않는다() {
        place(creator, OrderSide.SELL, 30, 1, OrderSource.ISSUANCE);
        engine.runOnce();
        at(EXPIRY);
        User buyer = newUser(100);
        place(buyer, OrderSide.BUY, 30, 1, null); // 만료 접수보다 먼저 들어온 매수

        expiry.expireDue();
        engine.runOnce();

        MarketCommand expire = commands.findAll().stream()
                .filter(c -> c.getType() == CommandType.EXPIRE).findFirst().orElseThrow();
        assertThat(expire.getStatus()).isEqualTo(CommandStatus.REJECTED);
        assertThat(expire.getRejectCode()).isEqualTo("MARKET_ORDER_NOT_OPEN");
        assertThat(marketOrders.findById(expire.getTargetOrderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(assets.findById(assetId).orElseThrow().getUnissuedQuantity()).isEqualTo(3); // 재고 복구 없음
        assertThat(activeCopy(buyer)).isTrue();
    }

    @Test
    void 만료_처리에_실패해_남은_주문은_다음_만료를_막지_않고_따로_셈한다() {
        User stuckBuyer = newUser(100);
        User nextBuyer = newUser(100);
        place(stuckBuyer, OrderSide.BUY, 20, 1, null);
        engine.runOnce();
        long stuckOrder = marketOrders.findAll().get(0).getId();
        // 만료 접수가 엔진 오류로 거절된 상태를 흉내냄(주문은 OPEN 그대로)
        jdbc.update("insert into market_commands (user_id, request_id, type, asset_id, status, reject_code, attempts, "
                        + "target_order_id, created_at) values (?, ?, 'EXPIRE', ?, 'REJECTED', 'MARKET_ENGINE_ERROR', 3, ?, ?)",
                stuckBuyer.getId(), MarketCommand.expireRequestId(stuckOrder), assetId, stuckOrder,
                java.sql.Timestamp.from(NOW));
        at(NOW.plusSeconds(60));
        place(nextBuyer, OrderSide.BUY, 21, 1, null);
        engine.runOnce();

        at(EXPIRY.plusSeconds(60));
        assertThat(expiry.expireDue()).isEqualTo(1); // 막힌 주문은 건너뛰고 새 만료 대상만 접수
        assertThat(marketOrders.countStuckExpired(EXPIRY.plusSeconds(60))).isEqualTo(1);
        engine.runOnce();

        assertThat(coin(nextBuyer)).isEqualTo(100);
        assertThat(marketOrders.findById(stuckOrder).orElseThrow().getStatus()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void 엔진이_꺼져_있거나_리스가_없으면_스케줄러는_아무것도_하지_않는다() {
        place(newUser(100), OrderSide.BUY, 20, 1, null);
        engine.runOnce();
        at(EXPIRY);

        new MarketOrderExpiryScheduler(marketOrders, commands, leaseManager, kstClock, transactionManager, false).run();
        new MarketOrderExpiryScheduler(marketOrders, commands, new NotHeld(), kstClock, transactionManager, true).run();
        assertThat(commands.findAll().stream().filter(c -> c.getType() == CommandType.EXPIRE)).isEmpty();

        new MarketOrderExpiryScheduler(marketOrders, commands, leaseManager, kstClock, transactionManager, true).run();
        assertThat(commands.findAll().stream().filter(c -> c.getType() == CommandType.EXPIRE)).hasSize(1);
    }

    private static final class NotHeld implements MarketEngineLeaseGuard {
        @Override public boolean holdsLease() { return false; }
        @Override public long fencingToken() { return -1; }
        @Override public void recordProgress() { }
        @Override public void fencedOut(long token) { }
    }

    private void at(Instant instant) {
        doReturn(instant).when(kstClock).instant();
    }

    private long place(User user, OrderSide side, int price, int quantity, OrderSource source) {
        return orders.place(user.getId(), new PlaceOrderRequest(UUID.randomUUID().toString(), assetId, side, price,
                quantity, source)).commandId();
    }

    private User newUser(int coin) {
        User user = users.save(User.signUp("market-expiry-" + UUID.randomUUID() + "@example.test"));
        wallets.save(UserWallet.createWithBalance(user, CurrencyType.COIN, coin));
        return user;
    }

    private int coin(User user) {
        return wallets.findByUserIdAndCurrencyType(user.getId(), CurrencyType.COIN).orElseThrow().getBalance();
    }

    private boolean activeCopy(User user) {
        return userItems.existsByUserIdAndItemIdAndDeletedAtIsNull(user.getId(), photoItem.getId());
    }
}
