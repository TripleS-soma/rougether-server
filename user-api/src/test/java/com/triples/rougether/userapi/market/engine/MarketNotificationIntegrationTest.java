package com.triples.rougether.userapi.market.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob;
import com.triples.rougether.domain.furniture.repository.FurnitureGenerationJobRepository;
import com.triples.rougether.domain.market.entity.CommandStatus;
import com.triples.rougether.domain.market.entity.MarketTrade;
import com.triples.rougether.domain.market.entity.OrderSide;
import com.triples.rougether.domain.market.entity.OrderSource;
import com.triples.rougether.domain.market.repository.MarketCommandRepository;
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
import com.triples.rougether.userapi.market.service.MarketAssetService;
import com.triples.rougether.userapi.market.service.MarketOrderCommandService;
import com.triples.rougether.userapi.notification.dto.NotificationSettingUpdateRequest;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import com.triples.rougether.userapi.notification.service.NotificationService;
import com.triples.rougether.userapi.notification.service.NotificationSettingService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

// 거래소 알림(#432)을 실제 커밋 경계로 검증함(테스트 트랜잭션 없음). 알림은 엔진 커밋 후 리스너에서 저장되므로
// runOnce() 가 끝나면 notification 테이블에 남아 있어야 함.
@SpringBootTest(properties = "market.engine.retry-delay-millis=0")
class MarketNotificationIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @Autowired private MatchingEngine engine;
    @Autowired private EngineLeaseManager leaseManager;
    @Autowired private MarketOrderCommandService orders;
    @Autowired private MarketAssetService assetService;
    @Autowired private MarketCommandRepository commands;
    @Autowired private MarketTradeRepository trades;
    @Autowired private NotificationSettingService settings;
    @Autowired private UserRepository users;
    @Autowired private UserWalletRepository wallets;
    @Autowired private ThemeRepository themes;
    @Autowired private ItemRepository items;
    @Autowired private UserItemRepository userItems;
    @Autowired private FurnitureGenerationJobRepository jobs;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private Clock kstClock;
    @MockitoBean private FcmPushExecutor fcm;
    @MockitoSpyBean private MarketNotifier notifier;
    @MockitoSpyBean private NotificationService notificationService;

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
        photoItem.updateNameTranslations(Map.of("en", "Cat sofa"));
        items.save(photoItem);
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
    void 대기하던_판매가_체결되면_판매자에게만_판매_체결_알림이_간다() {
        User buyer = newUser(100);
        long sellCommand = place(creator, OrderSide.SELL, 30, 2, OrderSource.ISSUANCE);
        engine.runOnce();
        place(buyer, OrderSide.BUY, 35, 1, null);
        engine.runOnce();

        List<Map<String, Object>> sellerNotes = notes(creator);
        assertThat(sellerNotes).singleElement().satisfies(n -> {
            assertThat(n.get("type")).isEqualTo("MARKET_SELL_FILLED");
            assertThat(n.get("ref_id")).isEqualTo(orderOf(sellCommand));
            assertThat(n.get("title")).isEqualTo("가구가 팔렸어요");
            assertThat(n.get("body")).isEqualTo("'고양이 소파'이(가) 30코인에 팔렸어요."); // 체결가(먼저 걸린 가격)
        });
        assertThat(notes(buyer)).isEmpty(); // taker 는 접수 결과 조회로 앎
        verify(fcm).push(any(), eq(creator.getId()), eq("가구가 팔렸어요"), eq("'고양이 소파'이(가) 30코인에 팔렸어요."), any());
    }

    @Test
    void 대기하던_구매가_체결되면_구매자에게_구매_체결_알림이_간다() {
        place(creator, OrderSide.SELL, 100, 1, OrderSource.ISSUANCE);
        engine.runOnce();
        User buyer = newUser(100);
        long buyCommand = place(buyer, OrderSide.BUY, 20, 1, null);
        engine.runOnce();
        place(creator, OrderSide.SELL, 15, 1, OrderSource.ISSUANCE);
        engine.runOnce();

        assertThat(notes(buyer)).singleElement().satisfies(n -> {
            assertThat(n.get("type")).isEqualTo("MARKET_BUY_FILLED");
            assertThat(n.get("ref_id")).isEqualTo(orderOf(buyCommand));
            assertThat(n.get("body")).isEqualTo("'고양이 소파'을(를) 20코인에 샀어요. 인벤토리를 확인해 보세요.");
        });
        assertThat(notes(creator)).noneMatch(n -> "MARKET_SELL_FILLED".equals(n.get("type"))); // 판매자가 taker
    }

    @Test
    void 발행_재고_주문이_세_명에게_팔리면_판매자_알림이_체결마다_간다() {
        long sellCommand = place(creator, OrderSide.SELL, 30, 3, OrderSource.ISSUANCE);
        engine.runOnce();
        for (int i = 0; i < 3; i++) {
            place(newUser(100), OrderSide.BUY, 30, 1, null);
        }
        engine.runOnce();

        assertThat(trades.findAll()).hasSize(3);
        assertThat(notes(creator)).hasSize(3).allSatisfy(n -> {
            assertThat(n.get("type")).isEqualTo("MARKET_SELL_FILLED");
            assertThat(n.get("ref_id")).isEqualTo(orderOf(sellCommand));
        });
    }

    @Test
    void 구매가_대기로_남으면_판매_대기자_전원에게_구매_대기_알림이_가고_즉시_체결된_구매는_보내지_않는다() {
        User reseller = newUser(100);
        User bidder = newUser(100);
        place(creator, OrderSide.SELL, 50, 1, OrderSource.ISSUANCE);
        engine.runOnce();
        place(reseller, OrderSide.BUY, 50, 1, null); // 즉시 체결 — 구매 대기 알림 없음
        engine.runOnce();
        place(reseller, OrderSide.SELL, 70, 1, OrderSource.INVENTORY);
        place(creator, OrderSide.SELL, 80, 1, OrderSource.ISSUANCE);
        place(creator, OrderSide.SELL, 90, 1, OrderSource.ISSUANCE); // 판매 대기 2건이어도 알림은 1건
        engine.runOnce();

        place(bidder, OrderSide.BUY, 40, 1, null);
        engine.runOnce();

        assertThat(bidNotes()).hasSize(2);
        for (User seller : List.of(reseller, creator)) {
            assertThat(notes(seller)).filteredOn(n -> "MARKET_BID_PLACED".equals(n.get("type")))
                    .singleElement().satisfies(n -> {
                        assertThat(n.get("ref_id")).isEqualTo(assetId);
                        assertThat(n.get("body")).isEqualTo("누군가 '고양이 소파'을(를) 40코인에 사고 싶어 해요.");
                    });
        }
        assertThat(notes(bidder)).isEmpty();
    }

    @Test
    void 구매_대기_알림은_판매자_종목당_24시간에_한_번만_간다() {
        User first = newUser(100);
        User second = newUser(100);
        place(creator, OrderSide.SELL, 80, 1, OrderSource.ISSUANCE);
        engine.runOnce();

        long firstBid = place(first, OrderSide.BUY, 40, 1, null);
        engine.runOnce();
        orders.cancel(first.getId(), orderOf(firstBid), UUID.randomUUID().toString()); // 걸었다 취소 반복
        engine.runOnce();
        place(first, OrderSide.BUY, 41, 1, null); // 가격을 바꿔도 같은 종목이면 막힘
        place(second, OrderSide.BUY, 39, 1, null);
        engine.runOnce();
        assertThat(bidNotes()).hasSize(1);

        // 24시간이 지나면 다시 보냄
        // 저장된 값 기준으로 당김(JDBC Timestamp 와 Hibernate Instant 의 시간대 해석 차이를 피함)
        jdbc.update("update notification set created_at = created_at - interval 25 hour "
                + "where type = 'MARKET_BID_PLACED'");
        User third = newUser(100);
        place(third, OrderSide.BUY, 38, 1, null);
        engine.runOnce();
        assertThat(bidNotes()).hasSize(2);
    }

    @Test
    void 영어_사용자에게는_가구_번역명으로_보낸다() {
        User buyer = newUser(100);
        jdbc.update("update users set language = 'en' where id = ?", creator.getId());
        place(creator, OrderSide.SELL, 30, 1, OrderSource.ISSUANCE);
        engine.runOnce();
        place(buyer, OrderSide.BUY, 30, 1, null);
        engine.runOnce();

        assertThat(notes(creator)).singleElement().satisfies(n -> {
            assertThat(n.get("title")).isEqualTo("Your furniture sold");
            assertThat(n.get("body")).isEqualTo("'Cat sofa' sold for 30 coins.");
        });
    }

    @Test
    void 거래소_알림을_끄면_내역은_남고_push만_막힌다() {
        settings.updateSettings(creator.getId(), new NotificationSettingUpdateRequest(null, null, null, null, false, null));
        User buyer = newUser(100);
        place(creator, OrderSide.SELL, 30, 1, OrderSource.ISSUANCE);
        engine.runOnce();
        place(buyer, OrderSide.BUY, 30, 1, null);
        engine.runOnce();

        assertThat(notes(creator)).singleElement()
                .satisfies(n -> assertThat(n.get("push_status")).isEqualTo("BLOCKED"));
        verify(fcm, never()).push(any(), eq(creator.getId()), any(), any(), any());
    }

    @Test
    void 마스터를_끄면_거래소_알림_push도_막힌다() {
        settings.updateSettings(creator.getId(), new NotificationSettingUpdateRequest(false, null, null, null, true, null));
        User buyer = newUser(100);
        place(creator, OrderSide.SELL, 30, 1, OrderSource.ISSUANCE);
        engine.runOnce();
        place(buyer, OrderSide.BUY, 30, 1, null);
        engine.runOnce();

        assertThat(notes(creator)).singleElement()
                .satisfies(n -> assertThat(n.get("push_status")).isEqualTo("BLOCKED"));
    }

    @Test
    void 엔진_처리가_롤백되면_그_시도의_알림은_남지_않고_재처리에서_한_번만_간다() {
        AtomicBoolean failedOnce = new AtomicBoolean();
        doAnswer(invocation -> {
            invocation.callRealMethod(); // 이벤트를 남긴 뒤 처리 트랜잭션을 깨뜨림
            if (failedOnce.compareAndSet(false, true)) {
                throw new IllegalStateException("boom after notification requested");
            }
            return null;
        }).when(notifier).makerFilled(any(), org.mockito.ArgumentMatchers.anyInt());
        User buyer = newUser(100);
        place(creator, OrderSide.SELL, 30, 1, OrderSource.ISSUANCE);
        engine.runOnce();
        place(buyer, OrderSide.BUY, 30, 1, null);

        assertThat(engine.runOnce()).isEqualTo(-1); // 롤백
        assertThat(trades.findAll()).isEmpty();
        assertThat(notes(creator)).isEmpty();

        assertThat(engine.runOnce()).isEqualTo(1); // 재처리 성공
        assertThat(trades.findAll()).hasSize(1);
        assertThat(notes(creator)).singleElement()
                .satisfies(n -> assertThat(n.get("type")).isEqualTo("MARKET_SELL_FILLED"));
    }

    @Test
    void 알림_저장이_실패해도_체결은_그대로_남는다() {
        User buyer = newUser(100);
        place(creator, OrderSide.SELL, 30, 1, OrderSource.ISSUANCE);
        engine.runOnce();
        // 엔진 커밋 뒤 알림 저장 단계만 실패시킴
        doThrow(new IllegalStateException("notification store down"))
                .when(notificationService).send(eq(creator.getId()), any(), any());
        place(buyer, OrderSide.BUY, 30, 1, null);

        assertThat(engine.runOnce()).isEqualTo(1); // 엔진 쪽으로 예외가 되던져지지 않음
        MarketTrade trade = trades.findAll().get(0);
        assertThat(trade.getPrice()).isEqualTo(30);
        assertThat(commands.findAll()).allSatisfy(c -> assertThat(c.getStatus()).isEqualTo(CommandStatus.APPLIED));
        assertThat(notes(creator)).isEmpty();
    }

    private List<Map<String, Object>> notes(User user) {
        return jdbc.queryForList("select type, ref_id, title, body, push_status from notification "
                + "where user_id = ? and type like 'MARKET_%' order by id", user.getId());
    }

    private List<Map<String, Object>> bidNotes() {
        return jdbc.queryForList("select user_id from notification where type = 'MARKET_BID_PLACED'");
    }

    private long place(User user, OrderSide side, int price, int quantity, OrderSource source) {
        return orders.place(user.getId(), new PlaceOrderRequest(UUID.randomUUID().toString(), assetId, side, price,
                quantity, source)).commandId();
    }

    private long orderOf(long commandId) {
        return commands.findById(commandId).orElseThrow().getOrderId();
    }

    private User newUser(int coin) {
        User user = users.save(User.signUp("market-notify-" + UUID.randomUUID() + "@example.test"));
        wallets.save(UserWallet.createWithBalance(user, CurrencyType.COIN, coin));
        return user;
    }
}
