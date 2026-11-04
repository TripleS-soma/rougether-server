package com.triples.rougether.userapi.market;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob;
import com.triples.rougether.domain.furniture.repository.FurnitureGenerationJobRepository;
import com.triples.rougether.domain.market.entity.*;
import com.triples.rougether.domain.market.repository.*;
import com.triples.rougether.domain.member.entity.*;
import com.triples.rougether.domain.member.repository.*;
import com.triples.rougether.domain.shared.*;
import com.triples.rougether.domain.shop.entity.*;
import com.triples.rougether.domain.shop.repository.*;
import com.triples.rougether.userapi.market.dto.*;
import com.triples.rougether.userapi.market.engine.*;
import com.triples.rougether.userapi.market.error.MarketErrorCode;
import com.triples.rougether.userapi.market.service.*;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(properties = {"market.fixed-price.enabled=true", "market.engine.retry-delay-millis=0"})
class MarketShopIntegrationTest {
    @Autowired MarketShopService shop;
    @Autowired MarketOrderCommandService oldOrders;
    @Autowired MarketAssetRepository assets;
    @Autowired MarketPurchaseRepository purchases;
    @Autowired MarketCommandRepository commands;
    @Autowired MarketOrderRepository orders;
    @Autowired UserRepository users;
    @Autowired UserWalletRepository wallets;
    @Autowired ItemRepository items;
    @Autowired ThemeRepository themes;
    @Autowired UserItemRepository inventory;
    @Autowired FurnitureGenerationJobRepository jobs;
    @Autowired MatchingEngine engine;
    @Autowired EngineLeaseManager leases;
    @Autowired MarketLegacyRetirement retirement;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean WalletHistoryRecorder histories;
    User creator, buyer;
    UserItem original;
    Long assetId;
    static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    @BeforeEach void setup() {
        cleanup();
        creator = user(0); buyer = user(100);
        var item = items.save(new Item(themes.findByCode("photo_furniture").orElseThrow(), "furniture", "positioned",
                null, null, "고정가 소파", null, null, "items/photo-furniture/" + UUID.randomUUID() + ".png", false, false));
        original = inventory.save(UserItem.create(creator, item));
        var job = FurnitureGenerationJob.create(creator.getId(), UUID.randomUUID().toString(), "a".repeat(64), "", NOW, NOW.plusSeconds(86400));
        job.succeed(item.getAssetKey(), original.getId(), NOW); jobs.save(job);
        assetId = shop.sell(creator.getId(), original.getId(), 30).assetId();
        leases.tick(); engine.invalidateBook();
    }

    @AfterEach void cleanup() {
        reset(histories);
        jdbc.update("delete from market_purchases");
        jdbc.update("delete from market_trades");
        jdbc.update("delete from market_orders");
        jdbc.update("delete from market_commands");
        jdbc.update("delete from market_assets");
        jdbc.update("update market_engine_lease set owner_token=null, lease_until=null where id=1");
        engine.invalidateBook();
    }

    @Test void 원본을_유지하면서_발행수량보다_많은_구매자에게_즉시_판매한다() {
        for (int i=0;i<3;i++) shop.buy(user(100).getId(), assetId, UUID.randomUUID().toString(), 30);
        assertThat(purchases.count()).isEqualTo(3);
        assertThat(balance(creator)).isEqualTo(87);
        assertThat(inventory.findById(original.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(assets.findById(assetId).orElseThrow().getTotalSupply()).isEqualTo(1);
        assertThat(orders.count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from wallet_histories where source_type='MARKET_PURCHASE' and source_id in (select id from market_purchases)", Long.class)).isEqualTo(6);
    }

    @Test void 동시_재시도에도_한번만_결제하고_판매중단_후에도_영수증을_재생한다() throws Exception {
        String request = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            List<Future<MarketPurchaseResponse>> results = new ArrayList<>();
            for (int i=0;i<2;i++) results.add(pool.submit(() -> {start.await();return shop.buy(buyer.getId(), assetId, request, 30);}));
            start.countDown();
            var first = results.get(0).get(20, TimeUnit.SECONDS);
            assertThat(results.get(1).get(20, TimeUnit.SECONDS)).isEqualTo(first);
            shop.update(creator.getId(), assetId, 50, false);
            jdbc.update("update users set deleted_at=? where id=?", java.sql.Timestamp.from(NOW), creator.getId());
            assertThat(shop.buy(buyer.getId(), assetId, request, 30)).isEqualTo(first);
            assertThat(balance(buyer)).isEqualTo(70);
            assertThat(purchases.count()).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }

    @Test void 가격변경과_잔액부족과_중복구매는_추가결제하지_않는다() {
        shop.update(creator.getId(), assetId, 40, true);
        fails(() -> shop.buy(buyer.getId(), assetId, UUID.randomUUID().toString(), 30), MarketErrorCode.PRICE_CHANGED);
        fails(() -> shop.buy(user(1).getId(), assetId, UUID.randomUUID().toString(), 40), MarketErrorCode.INSUFFICIENT_COIN);
        assertThat(balance(buyer)).isEqualTo(100);
        String key = UUID.randomUUID().toString();
        shop.buy(buyer.getId(), assetId, key, 40);
        fails(() -> shop.buy(buyer.getId(), assetId, key, 30), MarketErrorCode.REQUEST_CONFLICT);
        fails(() -> shop.buy(buyer.getId(), assetId, UUID.randomUUID().toString(), 40), MarketErrorCode.ALREADY_HOLDING);
        assertThat(balance(buyer)).isEqualTo(60);
    }

    @Test void 서로다른_요청으로_동시구매해도_한번만_결제한다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> results = new ArrayList<>();
            for (int i=0;i<2;i++) results.add(pool.submit(() -> {
                start.await();
                try {return shop.buy(buyer.getId(), assetId, UUID.randomUUID().toString(), 30);}
                catch (BusinessException e) {return e.getErrorCode();}
            }));
            start.countDown();
            List<Object> outcomes = List.of(results.get(0).get(20, TimeUnit.SECONDS), results.get(1).get(20, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(x -> x instanceof MarketPurchaseResponse)).hasSize(1);
            assertThat(outcomes).contains(MarketErrorCode.ALREADY_HOLDING);
            assertThat(balance(buyer)).isEqualTo(70);
            assertThat(purchases.count()).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }

    @Test void 두제작자가_서로의_가구를_동시에_사도_교착없이_정산한다() throws Exception {
        var item = items.save(new Item(themes.findByCode("photo_furniture").orElseThrow(), "furniture", "positioned",
                null, null, "두번째 소파", null, null, "items/photo-furniture/"+UUID.randomUUID()+".png", false, false));
        var copy = inventory.save(UserItem.create(buyer, item));
        var job = FurnitureGenerationJob.create(buyer.getId(), UUID.randomUUID().toString(), "b".repeat(64), "", NOW, NOW.plusSeconds(86400));
        job.succeed(item.getAssetKey(), copy.getId(), NOW); jobs.save(job);
        Long other = shop.sell(buyer.getId(), copy.getId(), 30).assetId();
        jdbc.update("update user_wallets set balance=100 where user_id=?", creator.getId());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            var first = pool.submit(() -> {start.await();return shop.buy(creator.getId(), other, UUID.randomUUID().toString(), 30);});
            var second = pool.submit(() -> {start.await();return shop.buy(buyer.getId(), assetId, UUID.randomUUID().toString(), 30);});
            start.countDown();
            first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
            assertThat(purchases.count()).isEqualTo(2);
            assertThat(balance(creator)).isEqualTo(99);
            assertThat(balance(buyer)).isEqualTo(99);
            assertThat(inventory.findById(original.getId()).orElseThrow().getDeletedAt()).isNull();
        } finally {pool.shutdownNow();}
    }

    @Test void 제작자만_가격을_바꾸며_판매중단과_제작자탈퇴후에는_구매할수없다() {
        fails(() -> shop.update(buyer.getId(), assetId, 1, true), MarketErrorCode.NOT_CREATOR);
        fails(() -> shop.sell(buyer.getId(), original.getId(), 1), MarketErrorCode.ITEM_NOT_OWNED);
        fails(() -> shop.buy(creator.getId(), assetId, UUID.randomUUID().toString(), 30), MarketErrorCode.ALREADY_HOLDING);
        shop.update(creator.getId(), assetId, 30, false);
        fails(() -> shop.buy(buyer.getId(), assetId, UUID.randomUUID().toString(), 30), MarketErrorCode.NOT_FOR_SALE);
        shop.update(creator.getId(), assetId, 30, true);
        jdbc.update("update users set deleted_at=? where id=?", java.sql.Timestamp.from(NOW), creator.getId());
        fails(() -> shop.buy(buyer.getId(), assetId, UUID.randomUUID().toString(), 30), MarketErrorCode.ASSET_NOT_FOUND);
        assertThat(shop.list(buyer.getId(), false, 0, 20).items()).isEmpty();
        assertThat(balance(buyer)).isEqualTo(100);
    }

    @Test void 판매원장_저장실패는_결제와_지급을_전부_롤백한다() {
        doThrow(new IllegalStateException("ledger unavailable")).when(org.springframework.test.util.AopTestUtils.<WalletHistoryRecorder>getUltimateTargetObject(histories))
                .record(any(), anyInt(), eq(WalletHistoryReason.MARKET_SALE), anyString(), anyLong());
        assertThatThrownBy(() -> shop.buy(buyer.getId(), assetId, UUID.randomUUID().toString(), 30)).isInstanceOf(IllegalStateException.class);
        assertThat(balance(buyer)).isEqualTo(100);
        assertThat(balance(creator)).isZero();
        assertThat(purchases.count()).isZero();
        assertThat(inventory.existsByUserIdAndItemIdAndDeletedAtIsNull(buyer.getId(), original.getItem().getId())).isFalse();
    }

    @Test void 구버전_주문생성과_되팔기는_막고_대기중인_원본은_정상_취소해_반환한다() {
        fails(() -> oldOrders.place(creator.getId(), new PlaceOrderRequest(UUID.randomUUID().toString(), assetId,
                OrderSide.SELL, 30, 1, OrderSource.INVENTORY)), MarketErrorCode.ORDERBOOK_RETIRED);
        original.deactivate(NOW);inventory.saveAndFlush(original);
        var cmd = commands.save(MarketCommand.place(creator.getId(), UUID.randomUUID().toString(), assetId,
                OrderSide.SELL, OrderSource.INVENTORY, 30, 1, 1, original.getId(), NOW));
        var order = orders.save(MarketOrder.open(cmd, 0, NOW));
        cmd.applied(order.getId(), NOW);commands.saveAndFlush(cmd);
        fails(() -> shop.buy(buyer.getId(), assetId, UUID.randomUUID().toString(), 30), MarketErrorCode.TRANSITION_PENDING);
        assertThat(retirement.enqueue()).isEqualTo(1);
        assertThat(retirement.enqueue()).isZero();
        engine.runOnce();
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(inventory.findById(original.getId()).orElseThrow().getDeletedAt()).isNull();
        shop.buy(buyer.getId(), assetId, UUID.randomUUID().toString(), 30);
        fails(() -> oldOrders.place(buyer.getId(), new PlaceOrderRequest(UUID.randomUUID().toString(), assetId,
                OrderSide.SELL, 40, 1, OrderSource.INVENTORY)), MarketErrorCode.ORDERBOOK_RETIRED);
    }

    @Test void 구버전_미처리_구매와_수량증가_접수는_체결하지_않고_맡긴것을_반환한다() {
        var money = wallets.findByUserIdAndCurrencyType(buyer.getId(), CurrencyType.COIN).orElseThrow();
        money.spend(30); wallets.saveAndFlush(money);
        jdbc.update("update market_assets set total_supply=5, unissued_quantity=3 where id=?", assetId);
        var buy = commands.save(MarketCommand.place(buyer.getId(), UUID.randomUUID().toString(), assetId,
                OrderSide.BUY, null, 30, 1, 30, null, NOW));
        var amend = commands.save(MarketCommand.amend(creator.getId(), UUID.randomUUID().toString(), assetId,
                99999L, 2, 1, NOW));

        engine.runOnce();
        engine.runOnce();

        assertThat(balance(buyer)).isEqualTo(100);
        assertThat(assets.findById(assetId).orElseThrow().getUnissuedQuantity()).isEqualTo(4);
        assertThat(commands.findById(buy.getId()).orElseThrow().getRejectCode()).isEqualTo("MARKET_ORDERBOOK_RETIRED");
        assertThat(commands.findById(amend.getId()).orElseThrow().getRejectCode()).isEqualTo("MARKET_ORDERBOOK_RETIRED");
        assertThat(orders.count()).isZero();
        assertThat(commands.hasUnsettledLegacyEscrow()).isFalse();
    }

    private User user(int amount) {
        var u = users.save(User.signUp());wallets.save(UserWallet.createWithBalance(u, CurrencyType.COIN, amount));return u;
    }
    private int balance(User u) {return wallets.findByUserIdAndCurrencyType(u.getId(), CurrencyType.COIN).orElseThrow().getBalance();}
    private void fails(Runnable work, MarketErrorCode code) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
