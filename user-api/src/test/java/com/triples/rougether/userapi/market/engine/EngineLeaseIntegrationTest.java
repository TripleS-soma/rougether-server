package com.triples.rougether.userapi.market.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob;
import com.triples.rougether.domain.furniture.repository.FurnitureGenerationJobRepository;
import com.triples.rougether.domain.market.entity.CommandStatus;
import com.triples.rougether.domain.market.entity.MarketCommand;
import com.triples.rougether.domain.market.entity.OrderSide;
import com.triples.rougether.domain.market.entity.OrderSource;
import com.triples.rougether.domain.market.repository.MarketCommandRepository;
import com.triples.rougether.domain.market.repository.MarketEngineLeaseRepository;
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
import com.triples.rougether.userapi.market.engine.EngineLeaseManager.Outcome;
import com.triples.rougether.userapi.market.service.MarketAssetService;
import com.triples.rougether.userapi.market.service.MarketOrderCommandService;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.transaction.PlatformTransactionManager;

// 엔진 리스·펜싱(#402)을 실제 MySQL 에서 검증함. 인스턴스 A·B 를 식별값이 다른 리스 매니저와 엔진으로 흉내내고,
// 두 인스턴스가 공유하는 조작 가능한 시계로 배포·GC 멈춤·정체를 재현함.
@SpringBootTest
class EngineLeaseIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(10);
    private static final Duration STALL = Duration.ofSeconds(30);

    @Autowired private CommandApplier applier;
    @Autowired private MarketEngineLeaseRepository leases;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MarketOrderCommandService orders;
    @Autowired private MarketAssetService assetService;
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

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-28T00:00:00Z"));
    private EngineLeaseManager leaseA;
    private EngineLeaseManager leaseB;
    private MatchingEngine engineA;
    private MatchingEngine engineB;
    private User creator;
    private Long assetId;

    @BeforeEach
    void setUp() {
        jdbc.update("update market_engine_lease set owner_token = null, lease_until = null where id = 1");
        leaseA = new EngineLeaseManager(leases, clock, transactionManager, "instance-a", false, LEASE, STALL, 3000);
        leaseB = new EngineLeaseManager(leases, clock, transactionManager, "instance-b", false, LEASE, STALL, 3000);
        engineA = new MatchingEngine(applier, leaseA, false, 50, 100, 0);
        engineB = new MatchingEngine(applier, leaseB, false, 50, 100, 0);

        creator = newUser(0);
        Item photoItem = items.save(new Item(themes.findByCode("photo_furniture").orElseThrow(), "furniture",
                "positioned", null, null, "고양이 소파", null, null, "furniture/photo/" + UUID.randomUUID() + ".png",
                false, false));
        UserItem creatorCopy = userItems.save(UserItem.create(creator, photoItem));
        FurnitureGenerationJob job = FurnitureGenerationJob.create(creator.getId(), UUID.randomUUID().toString(),
                "a".repeat(64), "고양이 소파", Instant.now(), Instant.now().plus(Duration.ofDays(1)));
        job.succeed(photoItem.getAssetKey(), creatorCopy.getId(), Instant.now());
        jobs.save(job);
        assetId = assetService.issue(creator.getId(), creatorCopy.getId(), 10).assetId();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from market_trades");
        jdbc.update("delete from market_orders");
        jdbc.update("delete from market_commands");
        jdbc.update("delete from market_assets");
        jdbc.update("update market_engine_lease set owner_token = null, lease_until = null where id = 1");
    }

    @Test
    void 리스가_살아_있으면_다른_인스턴스는_인수하지_못하고_만료되면_번호를_올려_인수한다() {
        assertThat(leaseA.tick()).isEqualTo(Outcome.TAKEN_OVER);
        long first = leaseA.fencingToken();
        assertThat(leaseB.tick()).isEqualTo(Outcome.HELD_BY_OTHER);

        clock.advance(Duration.ofSeconds(9));
        assertThat(leaseB.tick()).isEqualTo(Outcome.HELD_BY_OTHER);
        assertThat(leaseB.holdsLease()).isFalse();

        clock.advance(Duration.ofSeconds(2)); // A 가 연장하지 못한 채 10초가 지남
        assertThat(leaseB.tick()).isEqualTo(Outcome.TAKEN_OVER);
        assertThat(leaseB.fencingToken()).isEqualTo(first + 1);
        assertThat(leaseA.tick()).isEqualTo(Outcome.HELD_BY_OTHER);
        assertThat(leaseA.holdsLease()).isFalse();
    }

    @Test
    void 연장하는_동안은_리스가_유지된다() {
        leaseA.tick();
        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofSeconds(3));
            leaseA.recordProgress();
            assertThat(leaseA.tick()).isEqualTo(Outcome.EXTENDED);
            assertThat(leaseB.tick()).isEqualTo(Outcome.HELD_BY_OTHER);
        }
    }

    @Test
    void 옛_번호로_처리하면_펜싱에_막혀_아무것도_남지_않는다() {
        leaseA.tick();
        long stale = leaseA.fencingToken();
        long sell = place(creator, OrderSide.SELL, 30, 1, OrderSource.ISSUANCE);
        place(newUser(100), OrderSide.BUY, 30, 1, null);
        clock.advance(LEASE.plusSeconds(1)); // A 가 GC 로 멈춘 사이 B 가 인수
        leaseB.tick();

        assertThatThrownBy(() -> applier.apply(sell, new OrderBook(), stale)).isInstanceOf(FencedOutException.class);
        assertThat(commands.findById(sell).orElseThrow().getStatus()).isEqualTo(CommandStatus.PENDING);

        assertThat(engineA.runOnce()).isZero(); // A 는 아직 담당이라고 믿지만 펜싱에 막혀 내려놓음
        assertThat(leaseA.holdsLease()).isFalse();
        assertThat(marketOrders.count()).isZero();
        assertThat(trades.count()).isZero();
        assertThat(commands.findAll()).allSatisfy(c -> assertThat(c.getEngineSeq()).isNull());
    }

    @Test
    void 두_엔진이_동시에_돌아도_체결과_순번이_중복되지_않는다() throws Exception {
        leaseA.tick();
        place(creator, OrderSide.SELL, 30, 5, OrderSource.ISSUANCE);
        for (int i = 0; i < 8; i++) {
            place(newUser(100), OrderSide.BUY, 30, 1, null);
        }
        clock.advance(LEASE.plusSeconds(1)); // blue(A)가 멈춘 사이 green(B)이 인수, A 는 여전히 담당이라고 믿음
        leaseB.tick();
        assertThat(leaseA.holdsLease()).isTrue();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (MatchingEngine engine : List.of(engineA, engineB)) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int round = 0; round < 20; round++) {
                        engine.runOnce();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        List<MarketCommand> processed = commands.findAll();
        assertThat(processed).allSatisfy(c -> assertThat(c.getStatus()).isEqualTo(CommandStatus.APPLIED));
        assertThat(processed.stream().map(MarketCommand::getEngineSeq).distinct().count()).isEqualTo(processed.size());
        assertThat(trades.count()).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(distinct buyer_user_id) from market_trades", Long.class)).isEqualTo(5);
    }

    @Test
    void 인수한_엔진은_이전_담당의_순번과_호가창을_이어받는다() {
        leaseA.tick();
        place(creator, OrderSide.SELL, 30, 1, OrderSource.ISSUANCE);
        engineA.runOnce();
        long lastByA = leases.findById(com.triples.rougether.domain.market.entity.MarketEngineLease.SINGLETON_ID)
                .orElseThrow().getLastEngineSeq();

        clock.advance(LEASE.plusSeconds(1));
        leaseB.tick();
        long buy = place(newUser(100), OrderSide.BUY, 30, 1, null);
        engineB.runOnce();

        assertThat(commands.findById(buy).orElseThrow().getEngineSeq()).isEqualTo(lastByA + 1);
        assertThat(trades.count()).isEqualTo(1); // A 가 남긴 매도 주문을 B 가 다시 적재해 체결함
    }

    @Test
    void 엔진이_정체되면_리스를_내려놓고_다른_인스턴스가_인수한다() {
        leaseA.tick();
        // heartbeat 는 계속 돌지만 엔진은 진전이 없음(처리 중 멈춤)
        for (int second = 3; second <= 30; second += 3) {
            clock.advance(Duration.ofSeconds(3));
            assertThat(leaseA.tick()).isEqualTo(Outcome.EXTENDED);
        }
        clock.advance(Duration.ofSeconds(3));

        assertThat(leaseA.tick()).isEqualTo(Outcome.RELEASED);
        assertThat(leaseA.holdsLease()).isFalse();
        assertThat(leaseA.tick()).isEqualTo(Outcome.COOLING_DOWN); // 반납한 쪽은 바로 다시 잡지 않음
        assertThat(leaseB.tick()).isEqualTo(Outcome.TAKEN_OVER);
    }

    @Test
    void 종료할_때_리스를_반납하면_다음_인스턴스가_기다리지_않고_인수한다() {
        leaseA.tick();

        leaseA.releaseOnShutdown();

        assertThat(leaseB.tick()).isEqualTo(Outcome.TAKEN_OVER);
    }

    @Test
    void 인수당한_옛_엔진은_실패_횟수를_올릴_수_없다() {
        leaseA.tick();
        long stale = leaseA.fencingToken();
        long sell = place(creator, OrderSide.SELL, 30, 1, OrderSource.ISSUANCE);
        clock.advance(LEASE.plusSeconds(1));
        leaseB.tick();

        assertThatThrownBy(() -> applier.recordFailure(sell, stale)).isInstanceOf(FencedOutException.class);
        assertThat(commands.findById(sell).orElseThrow().getAttempts()).isZero();
    }

    private long place(User user, OrderSide side, int price, int quantity, OrderSource source) {
        return orders.place(user.getId(), new PlaceOrderRequest(UUID.randomUUID().toString(), assetId, side, price,
                quantity, source)).commandId();
    }

    private User newUser(int coin) {
        User user = users.save(User.signUp("market-lease-" + UUID.randomUUID() + "@example.test"));
        wallets.save(UserWallet.createWithBalance(user, CurrencyType.COIN, coin));
        return user;
    }
}
