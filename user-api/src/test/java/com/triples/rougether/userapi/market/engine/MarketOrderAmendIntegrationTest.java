package com.triples.rougether.userapi.market.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob;
import com.triples.rougether.domain.furniture.repository.FurnitureGenerationJobRepository;
import com.triples.rougether.domain.market.entity.CommandStatus;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 발행 재고 판매 주문 수량 수정(#433)을 실제 MySQL 에서 검증함. 엔진 스레드는 끄고 runOnce() 로 구동함.
// 발행 수량 5 → 제작자 보유분 1 을 뺀 발행 재고 4 로 시작.
@SpringBootTest(properties = "market.engine.retry-delay-millis=0")
class MarketOrderAmendIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @Autowired private MatchingEngine engine;
    @Autowired private CommandApplier applier;
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
    private Long assetId;

    @BeforeEach
    void setUp() {
        doReturn(NOW).when(kstClock).instant();
        doReturn(ZoneId.of("Asia/Seoul")).when(kstClock).getZone();
        creator = newUser(0);
        Item photoItem = items.save(new Item(themes.findByCode("photo_furniture").orElseThrow(), "furniture",
                "positioned", null, null, "고양이 소파", null, null, "furniture/photo/" + UUID.randomUUID() + ".png",
                false, false));
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
        jdbc.update("delete from notification where type like 'MARKET_%'");
        jdbc.update("delete from market_trades");
        jdbc.update("delete from market_orders");
        jdbc.update("delete from market_commands");
        jdbc.update("delete from market_assets");
        jdbc.update("update market_engine_lease set owner_token = null, lease_until = null where id = 1");
        engine.invalidateBook();
    }

    @Test
    void 줄이면_재고로_돌아가고_대기_순서가_유지된다() {
        long creatorOrder = creatorOrderBehindReseller(3); // 40 가격대: 제작자 3개 → 되판매자 1개 순
        assertThat(unissued()).isEqualTo(0);

        long command = amend(creatorOrder, 2);
        engine.runOnce();

        assertThat(commands.findById(command).orElseThrow().getStatus()).isEqualTo(CommandStatus.APPLIED);
        MarketOrder order = marketOrders.findById(creatorOrder).orElseThrow();
        assertThat(order.getQuantity()).isEqualTo(2);
        assertThat(order.getEscrowRemaining()).isEqualTo(2);
        assertThat(unissued()).isEqualTo(1);

        place(newUser(100), OrderSide.BUY, 40, 1, null);
        engine.runOnce();
        assertThat(lastTrade().getSellOrderId()).isEqualTo(creatorOrder); // 여전히 맨 앞
        assertSupplyBalanced();
    }

    @Test
    void 늘리면_재고에서_빠지고_같은_가격대_맨_뒤로_가며_재적재해도_유지된다() {
        long creatorOrder = creatorOrderBehindReseller(2);
        long resellerOrder = openSellOf(OrderSource.INVENTORY);
        assertThat(unissued()).isEqualTo(1);

        amend(creatorOrder, 3);
        engine.runOnce();

        assertThat(marketOrders.findById(creatorOrder).orElseThrow().getQuantity()).isEqualTo(3);
        assertThat(unissued()).isZero();
        engine.invalidateBook(); // DB 에서 다시 적재해도 순서가 남아야 함

        place(newUser(100), OrderSide.BUY, 40, 1, null);
        engine.runOnce();
        assertThat(lastTrade().getSellOrderId()).isEqualTo(resellerOrder); // 제작자 주문은 뒤로 밀림
        assertSupplyBalanced();
    }

    @Test
    void 늘린_주문은_재적재_없이도_메모리_호가창에서_맨_뒤로_간다() {
        long creatorOrder = creatorOrderBehindReseller(2);
        long resellerOrder = openSellOf(OrderSource.INVENTORY);

        amend(creatorOrder, 3);
        engine.runOnce();
        place(newUser(100), OrderSide.BUY, 40, 1, null); // 같은 runOnce 흐름, 호가창 캐시 그대로
        engine.runOnce();

        assertThat(lastTrade().getSellOrderId()).isEqualTo(resellerOrder);
    }

    @Test
    void 줄인_수량만큼만_체결되고_다_팔리면_주문이_끝난다() {
        long order = issuanceOrder(30, 3);
        amend(order, 2);
        engine.runOnce();
        for (int i = 0; i < 3; i++) {
            place(newUser(100), OrderSide.BUY, 30, 1, null);
        }

        engine.runOnce();

        assertThat(trades.findAll()).hasSize(2); // 호가창 캐시의 남은 수량도 2 로 줄었어야 함
        MarketOrder filled = marketOrders.findById(order).orElseThrow();
        assertThat(filled.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(filled.getFilledQuantity()).isEqualTo(2);
        assertSupplyBalanced();
    }

    @Test
    void 접수_뒤_체결로_팔린_수량_이하가_되면_엔진이_거절한다() {
        long order = issuanceOrder(30, 3);
        place(newUser(100), OrderSide.BUY, 30, 1, null);
        place(newUser(100), OrderSide.BUY, 30, 1, null);
        long command = amend(order, 2); // 접수 시점엔 팔린 수량 0

        engine.runOnce();

        MarketCommand rejected = commands.findById(command).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(CommandStatus.REJECTED);
        assertThat(rejected.getRejectCode()).isEqualTo("MARKET_AMEND_BELOW_FILLED");
        assertThat(marketOrders.findById(order).orElseThrow().getQuantity()).isEqualTo(3);
        assertSupplyBalanced();
    }

    @Test
    void 처리_전에_주문이_끝나면_늘리려고_뺀_재고를_돌려준다() {
        long order = issuanceOrder(30, 3);
        for (int i = 0; i < 3; i++) {
            place(newUser(100), OrderSide.BUY, 30, 1, null);
        }
        long command = amend(order, 4);
        assertThat(unissued()).isZero(); // 늘린 1개를 접수 때 미리 뺌

        engine.runOnce();

        assertThat(marketOrders.findById(order).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(commands.findById(command).orElseThrow().getRejectCode()).isEqualTo("MARKET_ORDER_NOT_OPEN");
        assertThat(unissued()).isEqualTo(1);
        assertSupplyBalanced();
    }

    @Test
    void 연달아_고쳐도_처리_시점_수량으로_정산해_재고_총량이_맞는다() {
        long order = issuanceOrder(30, 2);
        assertThat(unissued()).isEqualTo(2);
        amend(order, 3); // 1개 미리 뺌
        amend(order, 1); // 접수 시점 수량(2) 기준 줄이기 — 엔진에선 3 → 1

        engine.runOnce();

        assertThat(marketOrders.findById(order).orElseThrow().getQuantity()).isEqualTo(1);
        assertThat(unissued()).isEqualTo(3);
        assertSupplyBalanced();
    }

    @Test
    void 엔진_정산에서_재고가_모자라면_거절하고_주문은_그대로다() {
        long order = issuanceOrder(30, 3);
        issuanceOrder(31, 1); // 남은 재고 0
        assertThat(unissued()).isZero();
        // 접수가 예전 수량 기준으로 계산돼 미리 뺀 재고(0)보다 엔진 정산이 더 필요로 하는 경우를 직접 넣음
        long command = insertAmend(order, 4, 0);

        engine.runOnce();

        MarketCommand rejected = commands.findById(command).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(CommandStatus.REJECTED);
        assertThat(rejected.getRejectCode()).isEqualTo("MARKET_INSUFFICIENT_SUPPLY");
        assertThat(marketOrders.findById(order).orElseThrow().getQuantity()).isEqualTo(3);
        assertSupplyBalanced();
    }

    @Test
    void 거래가_정지되면_접수와_엔진_모두_막고_늘리려고_뺀_재고를_돌려준다() {
        long order = issuanceOrder(30, 2);
        long command = amend(order, 3); // 1개 미리 뺌
        assertThat(unissued()).isEqualTo(1);
        jdbc.update("update market_assets set status = 'SUSPENDED' where id = ?", assetId);

        engine.runOnce();

        assertThat(commands.findById(command).orElseThrow().getRejectCode()).isEqualTo("MARKET_ASSET_SUSPENDED");
        assertThat(marketOrders.findById(order).orElseThrow().getQuantity()).isEqualTo(2);
        assertThat(unissued()).isEqualTo(2);
        assertError(() -> amend(order, 1), MarketErrorCode.ASSET_SUSPENDED);
        assertSupplyBalanced();
    }

    @Test
    void 반복_실패로_거절되면_늘리려고_뺀_재고를_돌려주고_줄이기는_돌려줄_것이_없다() {
        long order = issuanceOrder(30, 2);
        long increase = amend(order, 3);
        long decrease = amend(order, 1);
        assertThat(unissued()).isEqualTo(1);

        applier.rejectAsEngineError(increase, leaseManager.fencingToken());
        applier.rejectAsEngineError(decrease, leaseManager.fencingToken());

        assertThat(commands.findById(increase).orElseThrow().getRejectCode()).isEqualTo("MARKET_ENGINE_ERROR");
        assertThat(commands.findById(decrease).orElseThrow().getRejectCode()).isEqualTo("MARKET_ENGINE_ERROR");
        assertThat(unissued()).isEqualTo(2);
        assertThat(marketOrders.findById(order).orElseThrow().getQuantity()).isEqualTo(2);
        assertSupplyBalanced();
    }

    @Test
    void 고칠_수_없는_주문과_잘못된_수량은_접수에서_막는다() {
        User buyer = newUser(100);
        long issuance = issuanceOrder(30, 2);
        long buyOrder = orderOf(place(buyer, OrderSide.BUY, 10, 1, null));
        engine.runOnce();

        assertError(() -> orders.amend(buyer.getId(), buyOrder, uuid(), 2), MarketErrorCode.ORDER_NOT_AMENDABLE);
        assertError(() -> orders.amend(creator.getId(), issuance, uuid(), 2), MarketErrorCode.AMEND_SAME_QUANTITY);
        assertError(() -> orders.amend(buyer.getId(), issuance, uuid(), 3), MarketErrorCode.ORDER_NOT_FOUND);
        assertError(() -> orders.amend(creator.getId(), issuance, uuid(), 5), MarketErrorCode.INSUFFICIENT_SUPPLY);

        place(newUser(100), OrderSide.BUY, 30, 1, null); // buyer 는 이미 구매 대기 중이라 다른 사람이 삼
        engine.runOnce(); // 1개 팔림
        assertError(() -> orders.amend(creator.getId(), issuance, uuid(), 1), MarketErrorCode.AMEND_BELOW_FILLED);

        orders.cancel(creator.getId(), issuance, uuid());
        engine.runOnce();
        assertError(() -> orders.amend(creator.getId(), issuance, uuid(), 3), MarketErrorCode.ORDER_NOT_OPEN);
        assertSupplyBalanced();
    }

    @Test
    void 보유분_판매_주문은_고칠_수_없다() {
        long inventory = orderOf(place(creator, OrderSide.SELL, 50, 1, OrderSource.INVENTORY));
        engine.runOnce();
        assertError(() -> orders.amend(creator.getId(), inventory, uuid(), 2), MarketErrorCode.ORDER_NOT_AMENDABLE);
    }

    @Test
    void 같은_requestId_재요청은_같은_접수를_돌려주고_다른_수량이면_충돌이다() {
        long order = issuanceOrder(30, 2);
        String requestId = uuid();

        long first = orders.amend(creator.getId(), order, requestId, 3).commandId();
        long again = orders.amend(creator.getId(), order, requestId, 3).commandId();

        assertThat(again).isEqualTo(first);
        assertThat(unissued()).isEqualTo(1); // 재고는 한 번만 뺌
        assertError(() -> orders.amend(creator.getId(), order, requestId, 4), MarketErrorCode.REQUEST_CONFLICT);
    }

    // 40 가격대에 제작자 발행 재고 3개 → 되판매자 보유분 1개 순서로 대기시킴. 제작자 주문 id 반환.
    private long creatorOrderBehindReseller(int quantity) {
        User reseller = newUser(100);
        place(creator, OrderSide.SELL, 30, 1, OrderSource.ISSUANCE);
        engine.runOnce();
        place(reseller, OrderSide.BUY, 30, 1, null);
        engine.runOnce();
        long creatorOrder = orderOf(place(creator, OrderSide.SELL, 40, quantity, OrderSource.ISSUANCE));
        engine.runOnce();
        place(reseller, OrderSide.SELL, 40, 1, OrderSource.INVENTORY);
        engine.runOnce();
        return creatorOrder;
    }

    private long issuanceOrder(int price, int quantity) {
        long order = orderOf(place(creator, OrderSide.SELL, price, quantity, OrderSource.ISSUANCE));
        engine.runOnce();
        return order;
    }

    private long openSellOf(OrderSource source) {
        return marketOrders.findAll().stream().filter(o -> o.isOpen() && o.getSource() == source)
                .map(MarketOrder::getId).findFirst().orElseThrow();
    }

    private long insertAmend(long orderId, int quantity, int escrow) {
        String requestId = uuid();
        jdbc.update("insert into market_commands (user_id, request_id, type, asset_id, side, source, quantity, "
                        + "escrow_amount, target_order_id, status, attempts, created_at) "
                        + "values (?, ?, 'AMEND', ?, 'SELL', 'ISSUANCE', ?, ?, ?, 'PENDING', 0, ?)",
                creator.getId(), requestId, assetId, quantity, escrow, orderId, java.sql.Timestamp.from(NOW));
        return jdbc.queryForObject("select id from market_commands where user_id = ? and request_id = ?", Long.class,
                creator.getId(), requestId);
    }

    private long amend(long orderId, int quantity) {
        return orders.amend(creator.getId(), orderId, uuid(), quantity).commandId();
    }

    private long place(User user, OrderSide side, int price, int quantity, OrderSource source) {
        return orders.place(user.getId(), new PlaceOrderRequest(uuid(), assetId, side, price, quantity, source))
                .commandId();
    }

    private long orderOf(long commandId) {
        engine.runOnce();
        return commands.findById(commandId).orElseThrow().getOrderId();
    }

    private int unissued() {
        return assets.findById(assetId).orElseThrow().getUnissuedQuantity();
    }

    private MarketTrade lastTrade() {
        List<MarketTrade> all = trades.findAll();
        return all.get(all.size() - 1);
    }

    // 발행 재고 총량 불변식: 남은 재고 + 발행 재고 판매 주문이 맡고 있는 수량 + 발행 재고에서 팔린 수량
    //   + 처리 전 수정 접수가 미리 뺀 수량 = 발행 수량 - 1(제작자 보유분)
    private void assertSupplyBalanced() {
        int total = assets.findById(assetId).orElseThrow().getTotalSupply();
        int escrowed = marketOrders.findAll().stream()
                .filter(o -> o.getSource() == OrderSource.ISSUANCE && o.isOpen())
                .mapToInt(MarketOrder::getEscrowRemaining).sum();
        int sold = marketOrders.findAll().stream().filter(o -> o.getSource() == OrderSource.ISSUANCE)
                .mapToInt(MarketOrder::getFilledQuantity).sum();
        int pending = commands.findAll().stream().filter(MarketCommand::isPending)
                .filter(c -> c.getSource() == OrderSource.ISSUANCE).mapToInt(MarketCommand::getEscrowAmount).sum();
        assertThat(unissued() + escrowed + sold + pending).isEqualTo(total - 1);
    }

    private User newUser(int coin) {
        User user = users.save(User.signUp("market-amend-" + UUID.randomUUID() + "@example.test"));
        wallets.save(UserWallet.createWithBalance(user, CurrencyType.COIN, coin));
        return user;
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, MarketErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
