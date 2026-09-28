package com.triples.rougether.userapi.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob;
import com.triples.rougether.domain.furniture.repository.FurnitureGenerationJobRepository;
import com.triples.rougether.domain.market.entity.CommandStatus;
import com.triples.rougether.domain.market.entity.CommandType;
import com.triples.rougether.domain.market.entity.MarketCommand;
import com.triples.rougether.domain.market.entity.OrderSide;
import com.triples.rougether.domain.market.entity.OrderSource;
import com.triples.rougether.domain.market.repository.MarketAssetRepository;
import com.triples.rougether.domain.market.repository.MarketCommandRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.entity.RoomItemPlacement;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.domain.room.repository.RoomItemPlacementRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.market.dto.MarketCommandAcceptedResponse;
import com.triples.rougether.userapi.market.dto.MarketCommandResponse;
import com.triples.rougether.userapi.market.dto.PlaceOrderRequest;
import com.triples.rougether.userapi.market.error.MarketErrorCode;
import com.triples.rougether.userapi.market.service.MarketAssetService;
import com.triples.rougether.userapi.market.service.MarketCommandQueryService;
import com.triples.rougether.userapi.market.service.MarketOrderCommandService;
import java.math.BigDecimal;
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

// 거래소 주문 접수·취소·접수 결과 조회(#400)를 실제 MySQL 에서 검증함. 에스크로·1인 1개·requestId 멱등이 핵심.
@SpringBootTest
class MarketOrderIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @Autowired private MarketOrderCommandService orders;
    @Autowired private MarketCommandQueryService commandQuery;
    @Autowired private MarketAssetService assetService;
    @Autowired private MarketAssetRepository assets;
    @Autowired private MarketCommandRepository commands;
    @Autowired private UserRepository users;
    @Autowired private UserWalletRepository wallets;
    @Autowired private ThemeRepository themes;
    @Autowired private ItemRepository items;
    @Autowired private UserItemRepository userItems;
    @Autowired private FurnitureGenerationJobRepository jobs;
    @Autowired private PersonalRoomRepository rooms;
    @Autowired private RoomItemPlacementRepository placements;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private Clock kstClock;

    private User creator;
    private User buyer;
    private Item photoItem;
    private UserItem creatorCopy;
    private Long assetId;

    @BeforeEach
    void setUp() {
        // 스케줄러 스레드(채팅 등)가 같은 시계 mock 을 부를 수 있어, 마지막 호출에 기대는 when() 대신 doReturn() 으로 스텁함
        doReturn(NOW).when(kstClock).instant();
        doReturn(ZoneId.of("Asia/Seoul")).when(kstClock).getZone();
        creator = newUser(0);
        buyer = newUser(100);
        photoItem = items.save(new Item(themes.findByCode("photo_furniture").orElseThrow(), "furniture", "positioned",
                null, null, "고양이 소파", null, null, "furniture/photo/" + UUID.randomUUID() + ".png", false, false));
        creatorCopy = userItems.save(UserItem.create(creator, photoItem));
        FurnitureGenerationJob job = FurnitureGenerationJob.create(creator.getId(), UUID.randomUUID().toString(),
                "a".repeat(64), "고양이 소파", NOW, NOW.plus(Duration.ofDays(1)));
        job.succeed(photoItem.getAssetKey(), creatorCopy.getId(), NOW);
        jobs.save(job);
        assetId = assetService.issue(creator.getId(), creatorCopy.getId(), 5).assetId();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from market_trades");
        jdbc.update("delete from market_orders");
        jdbc.update("delete from market_commands");
        jdbc.update("delete from market_assets");
    }

    @Test
    void 매수하면_코인을_맡기고_원장과_접수를_한_트랜잭션에_남긴다() {
        MarketCommandAcceptedResponse accepted = orders.place(buyer.getId(), buy(30));

        assertThat(accepted.status()).isEqualTo(CommandStatus.PENDING);
        assertThat(coin(buyer)).isEqualTo(70);
        MarketCommand command = commands.findById(accepted.commandId()).orElseThrow();
        assertThat(command.getType()).isEqualTo(CommandType.PLACE);
        assertThat(command.getSide()).isEqualTo(OrderSide.BUY);
        assertThat(command.getEscrowAmount()).isEqualTo(30);
        assertThat(command.getEngineSeq()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from wallet_histories where user_id = ? and reason = 'MARKET_ORDER_ESCROW' "
                + "and amount = -30 and balance_after = 70 and source_type = 'MARKET_COMMAND' and source_id = ?",
                Long.class, buyer.getId(), command.getId())).isEqualTo(1);
    }

    @Test
    void 코인이_부족하면_아무것도_남기지_않는다() {
        assertError(() -> orders.place(buyer.getId(), buy(101)), MarketErrorCode.INSUFFICIENT_COIN);

        assertThat(coin(buyer)).isEqualTo(100);
        assertThat(commands.count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from wallet_histories where user_id = ? and reason = 'MARKET_ORDER_ESCROW'",
                Long.class, buyer.getId())).isZero();
    }

    @Test
    void 내_가구를_판매_등록하면_방에서_빠지고_인벤토리에서_숨겨진다() {
        PersonalRoom room = rooms.save(PersonalRoom.create(creator));
        placements.save(RoomItemPlacement.place(room, creatorCopy, new BigDecimal("0.5"), new BigDecimal("0.5"),
                1, BigDecimal.ONE, 0, false));

        MarketCommandAcceptedResponse accepted = orders.place(creator.getId(),
                sell(40, 1, OrderSource.INVENTORY));

        assertThat(jdbc.queryForObject("select count(*) from room_item_placements where user_item_id = ?",
                Long.class, creatorCopy.getId())).isZero();
        assertThat(userItems.findById(creatorCopy.getId()).orElseThrow().getDeletedAt()).isEqualTo(NOW);
        assertThat(userItems.findInventoryByUserId(creator.getId(), null)).isEmpty();
        MarketCommand command = commands.findById(accepted.commandId()).orElseThrow();
        assertThat(command.getEscrowUserItemId()).isEqualTo(creatorCopy.getId());
        assertThat(command.getEscrowAmount()).isEqualTo(1);
    }

    @Test
    void 보유하지_않은_가구는_판매할_수_없다() {
        assertError(() -> orders.place(buyer.getId(), sell(40, 1, OrderSource.INVENTORY)), MarketErrorCode.ITEM_NOT_OWNED);
    }

    @Test
    void 제작자는_발행_재고를_여러_개_판매할_수_있고_재고가_줄어든다() {
        MarketCommandAcceptedResponse accepted = orders.place(creator.getId(), sell(25, 3, OrderSource.ISSUANCE));

        assertThat(assets.findById(assetId).orElseThrow().getUnissuedQuantity()).isEqualTo(1);
        assertThat(commands.findById(accepted.commandId()).orElseThrow().getEscrowAmount()).isEqualTo(3);
    }

    @Test
    void 발행_재고는_제작자만_팔_수_있고_남은_재고를_넘을_수_없다() {
        assertError(() -> orders.place(buyer.getId(), sell(25, 1, OrderSource.ISSUANCE)), MarketErrorCode.NOT_CREATOR);
        assertError(() -> orders.place(creator.getId(), sell(25, 5, OrderSource.ISSUANCE)), MarketErrorCode.INSUFFICIENT_SUPPLY);

        assertThat(assets.findById(assetId).orElseThrow().getUnissuedQuantity()).isEqualTo(4);
        assertThat(commands.count()).isZero();
    }

    @Test
    void 같은_requestId를_동시에_보내도_접수와_코인_차감은_한_번이다() throws Exception {
        PlaceOrderRequest request = buy(30);

        List<MarketCommandAcceptedResponse> responses = concurrently(4, () -> orders.place(buyer.getId(), request));

        assertThat(responses).extracting(MarketCommandAcceptedResponse::commandId).containsOnly(responses.get(0).commandId());
        assertThat(commands.count()).isEqualTo(1);
        assertThat(coin(buyer)).isEqualTo(70);
    }

    @Test
    void 같은_requestId에_다른_내용을_보내면_거절한다() {
        PlaceOrderRequest first = buy(30);
        orders.place(buyer.getId(), first);

        assertError(() -> orders.place(buyer.getId(), new PlaceOrderRequest(first.requestId(), assetId,
                OrderSide.BUY, 31, 1, null)), MarketErrorCode.REQUEST_CONFLICT);
        long open = openOrder(buyer, OrderSide.BUY, null, null, 30);
        assertError(() -> orders.cancel(buyer.getId(), open, first.requestId()), MarketErrorCode.REQUEST_CONFLICT);
        assertThat(coin(buyer)).isEqualTo(70);
    }

    @Test
    void 다른_requestId로_동시에_매수해도_1인_1개라_하나만_접수된다() throws Exception {
        List<Object> results = concurrently(4, () -> {
            try {
                return orders.place(buyer.getId(), buy(30));
            } catch (BusinessException e) {
                return e.getErrorCode();
            }
        });

        assertThat(results).filteredOn(MarketCommandAcceptedResponse.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(MarketErrorCode.ALREADY_HOLDING::equals).hasSize(3);
        assertThat(coin(buyer)).isEqualTo(70);
    }

    @Test
    void 보유_중이거나_구매_대기_중이면_다시_살_수_없다() {
        // 활성 보유
        assertError(() -> orders.place(creator.getId(), buy(30)), MarketErrorCode.ALREADY_HOLDING);
        // 엔진 처리 전 매수 접수
        orders.place(buyer.getId(), buy(30));
        assertError(() -> orders.place(buyer.getId(), buy(31)), MarketErrorCode.ALREADY_HOLDING);
        // 미체결 매수 주문
        User other = newUser(100);
        openOrder(other, OrderSide.BUY, null, null, 30);
        assertError(() -> orders.place(other.getId(), buy(30)), MarketErrorCode.ALREADY_HOLDING);
    }

    @Test
    void 판매_등록으로_맡긴_가구가_있으면_같은_가구를_살_수_없다() {
        orders.place(creator.getId(), sell(40, 1, OrderSource.INVENTORY));

        assertError(() -> orders.place(creator.getId(), buy(30)), MarketErrorCode.ALREADY_HOLDING);
    }

    @Test
    void 거래_정지되거나_없는_종목에는_주문할_수_없다() {
        assertError(() -> orders.place(buyer.getId(), new PlaceOrderRequest(UUID.randomUUID().toString(), 999_999L,
                OrderSide.BUY, 30, 1, null)), MarketErrorCode.ASSET_NOT_FOUND);
        jdbc.update("update market_assets set status = 'SUSPENDED' where id = ?", assetId);
        assertError(() -> orders.place(buyer.getId(), buy(30)), MarketErrorCode.ASSET_SUSPENDED);
    }

    @Test
    void 대기_중인_내_주문만_취소_접수할_수_있다() {
        long open = openOrder(buyer, OrderSide.BUY, null, null, 30);
        String requestId = UUID.randomUUID().toString();

        MarketCommandAcceptedResponse accepted = orders.cancel(buyer.getId(), open, requestId);
        MarketCommandAcceptedResponse retried = orders.cancel(buyer.getId(), open, requestId);

        assertThat(retried.commandId()).isEqualTo(accepted.commandId());
        MarketCommand command = commands.findById(accepted.commandId()).orElseThrow();
        assertThat(command.getType()).isEqualTo(CommandType.CANCEL);
        assertThat(command.getTargetOrderId()).isEqualTo(open);
        assertError(() -> orders.cancel(creator.getId(), open, UUID.randomUUID().toString()), MarketErrorCode.ORDER_NOT_FOUND);
        jdbc.update("update market_orders set status = 'CANCELLED' where id = ?", open);
        assertError(() -> orders.cancel(buyer.getId(), open, UUID.randomUUID().toString()), MarketErrorCode.ORDER_NOT_OPEN);
    }

    @Test
    void 접수_결과는_본인만_보고_처리되면_주문을_함께_보여준다() {
        MarketCommandAcceptedResponse accepted = orders.place(buyer.getId(), buy(30));
        assertThat(commandQuery.get(buyer.getId(), accepted.commandId()).order()).isNull();
        assertError(() -> commandQuery.get(creator.getId(), accepted.commandId()), MarketErrorCode.COMMAND_NOT_FOUND);

        long order = openOrder(buyer, OrderSide.BUY, null, null, 30);
        jdbc.update("update market_commands set status = 'APPLIED', order_id = ? where id = ?", order, accepted.commandId());

        MarketCommandResponse applied = commandQuery.get(buyer.getId(), accepted.commandId());
        assertThat(applied.status()).isEqualTo(CommandStatus.APPLIED);
        assertThat(applied.order().orderId()).isEqualTo(order);
        assertThat(applied.order().name()).isEqualTo("고양이 소파");
        assertThat(applied.order().price()).isEqualTo(30);
    }

    private PlaceOrderRequest buy(int price) {
        return new PlaceOrderRequest(UUID.randomUUID().toString(), assetId, OrderSide.BUY, price, 1, null);
    }

    private PlaceOrderRequest sell(int price, int quantity, OrderSource source) {
        return new PlaceOrderRequest(UUID.randomUUID().toString(), assetId, OrderSide.SELL, price, quantity, source);
    }

    // 엔진(#401) 전이라 주문 row 는 직접 넣음. 접수 row 와 1:1.
    private long openOrder(User owner, OrderSide side, OrderSource source, Long userItemId, int price) {
        jdbc.update("insert into market_commands (user_id, request_id, type, asset_id, side, source, price, quantity, "
                        + "escrow_amount, status, attempts, created_at) values (?, ?, 'PLACE', ?, ?, ?, ?, 1, ?, 'APPLIED', 0, ?)",
                owner.getId(), UUID.randomUUID().toString(), assetId, side.name(), source == null ? null : source.name(),
                price, price, Timestamp.from(NOW));
        Long commandId = jdbc.queryForObject("select max(id) from market_commands", Long.class);
        jdbc.update("insert into market_orders (command_id, asset_id, user_id, side, source, user_item_id, price, quantity, "
                        + "filled_quantity, escrow_remaining, engine_seq, status, expires_at, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 1, 0, ?, ?, 'OPEN', ?, ?, ?)",
                commandId, assetId, owner.getId(), side.name(), source == null ? null : source.name(), userItemId,
                price, price, commandId, Timestamp.from(NOW.plus(Duration.ofDays(7))), Timestamp.from(NOW), Timestamp.from(NOW));
        return jdbc.queryForObject("select id from market_orders where command_id = ?", Long.class, commandId);
    }

    private User newUser(int coin) {
        User user = users.save(User.signUp("market-order-" + UUID.randomUUID() + "@example.test"));
        wallets.save(UserWallet.createWithBalance(user, CurrencyType.COIN, coin));
        return user;
    }

    private int coin(User user) {
        return wallets.findByUserIdAndCurrencyType(user.getId(), CurrencyType.COIN).orElseThrow().getBalance();
    }

    private <T> List<T> concurrently(int threads, java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, MarketErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
