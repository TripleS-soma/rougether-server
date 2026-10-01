package com.triples.rougether.userapi.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob;
import com.triples.rougether.domain.furniture.repository.FurnitureGenerationJobRepository;
import com.triples.rougether.domain.market.entity.OrderSide;
import com.triples.rougether.domain.market.entity.OrderSource;
import com.triples.rougether.domain.market.entity.OrderStatus;
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
import com.triples.rougether.userapi.market.dto.MarketAssetListResponse;
import com.triples.rougether.userapi.market.dto.MarketAssetListResponse.AssetCard;
import com.triples.rougether.userapi.market.dto.MarketAssetResponse;
import com.triples.rougether.userapi.market.dto.MarketAssetResponse.PriceLevel;
import com.triples.rougether.userapi.market.dto.MarketOrderResponse;
import com.triples.rougether.userapi.market.dto.MyMarketOrderListResponse;
import com.triples.rougether.userapi.market.dto.MyMarketOrderStatus;
import com.triples.rougether.userapi.market.dto.PlaceOrderRequest;
import com.triples.rougether.userapi.market.engine.EngineLeaseManager;
import com.triples.rougether.userapi.market.engine.MatchingEngine;
import com.triples.rougether.userapi.market.error.MarketErrorCode;
import com.triples.rougether.userapi.market.service.MarketAssetService;
import com.triples.rougether.userapi.market.service.MarketOrderCommandService;
import com.triples.rougether.userapi.market.service.MarketQueryService;
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

// 거래소 조회(#404)를 실제 MySQL 에서 검증함. 호가·체결 데이터는 주문 API 와 엔진(runOnce)으로 만듦.
@SpringBootTest
class MarketQueryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @Autowired private MarketQueryService query;
    @Autowired private MarketOrderCommandService orders;
    @Autowired private MarketAssetService assetService;
    @Autowired private MatchingEngine engine;
    @Autowired private EngineLeaseManager leaseManager;
    @Autowired private UserRepository users;
    @Autowired private UserWalletRepository wallets;
    @Autowired private ThemeRepository themes;
    @Autowired private ItemRepository items;
    @Autowired private UserItemRepository userItems;
    @Autowired private FurnitureGenerationJobRepository jobs;
    @Autowired private JdbcTemplate jdbc;
    // 스케줄러 스레드가 같은 시계 mock 을 부를 수 있어 when() 대신 doReturn() 으로 스텁함
    @MockitoBean private Clock kstClock;

    private User creator;
    private Long assetId;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from market_trades");
        jdbc.update("delete from market_orders");
        jdbc.update("delete from market_commands");
        jdbc.update("delete from market_assets");
        doReturn(NOW).when(kstClock).instant();
        doReturn(ZoneId.of("Asia/Seoul")).when(kstClock).getZone();
        creator = newUser("영희", 0);
        assetId = issue(creator, 10);
        jdbc.update("update market_engine_lease set owner_token = null, lease_until = null where id = 1");
        leaseManager.tick();
        assertThat(leaseManager.holdsLease()).isTrue();
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
    void 호가는_가격대별로_합산되고_판매는_싼_순_구매는_비싼_순이다() {
        place(creator, OrderSide.SELL, 30, 2, OrderSource.ISSUANCE);
        place(creator, OrderSide.SELL, 30, 1, OrderSource.ISSUANCE);
        place(creator, OrderSide.SELL, 35, 1, OrderSource.ISSUANCE);
        place(newUser(null, 100), OrderSide.BUY, 25, 1, null);
        place(newUser(null, 100), OrderSide.BUY, 25, 1, null);
        place(newUser(null, 100), OrderSide.BUY, 20, 1, null);
        engine.runOnce();

        MarketAssetResponse detail = query.getAsset(creator.getId(), assetId);

        assertThat(detail.asks()).containsExactly(new PriceLevel(30, 3), new PriceLevel(35, 1));
        assertThat(detail.bids()).containsExactly(new PriceLevel(25, 2), new PriceLevel(20, 1));
        assertThat(detail.unissuedQuantity()).isEqualTo(5);
    }

    @Test
    void 호가는_각_방향_최대_열_단계까지만_보여준다() {
        for (int price = 1; price <= 11; price++) {
            place(newUser(null, 100), OrderSide.BUY, price, 1, null);
        }
        engine.runOnce();

        MarketAssetResponse detail = query.getAsset(creator.getId(), assetId);

        assertThat(detail.bids()).hasSize(10);
        assertThat(detail.bids().get(0)).isEqualTo(new PriceLevel(11, 1));
        assertThat(detail.bids().get(9)).isEqualTo(new PriceLevel(2, 1));
    }

    @Test
    void 체결되면_최근_체결가와_체결_목록이_나오고_목록_카드에_요약된다() {
        User buyer = newUser(null, 100);
        place(creator, OrderSide.SELL, 30, 1, OrderSource.ISSUANCE);
        place(creator, OrderSide.SELL, 40, 2, OrderSource.ISSUANCE);
        place(buyer, OrderSide.BUY, 30, 1, null);
        engine.runOnce();

        assertThat(query.getAsset(buyer.getId(), assetId).lastTradePrice()).isEqualTo(30);
        assertThat(query.trades(assetId, 0, 20).items()).singleElement()
                .satisfies(t -> assertThat(t.price()).isEqualTo(30));
        AssetCard card = cardOf(query.listAssets(creator.getId(), 0, 20));
        assertThat(card.bestAskPrice()).isEqualTo(40);
        assertThat(card.askQuantity()).isEqualTo(2);
        assertThat(card.lastTradePrice()).isEqualTo(30);
        assertThat(card.creatorNickname()).isEqualTo("영희");
        assertThat(card.name()).isEqualTo("고양이 소파");
    }

    @Test
    void 거래_정지된_종목은_목록에_나오지_않고_최근_상장순이다() {
        Long later = issue(newUser("철수", 0), 3);
        Long suspended = issue(newUser("민수", 0), 3);
        jdbc.update("update market_assets set status = 'SUSPENDED' where id = ?", suspended);

        MarketAssetListResponse list = query.listAssets(creator.getId(), 0, 20);

        assertThat(list.items()).extracting(AssetCard::assetId).containsExactly(later, assetId);
        assertThat(list.totalElements()).isEqualTo(2);
        assertThat(list.items().get(1).bestAskPrice()).isNull();
        assertThat(list.items().get(1).askQuantity()).isZero();
    }

    @Test
    void 제작자_여부와_보유_여부는_요청자_기준이다() {
        User buyer = newUser(null, 100);

        MarketAssetResponse forCreator = query.getAsset(creator.getId(), assetId);
        MarketAssetResponse forBuyer = query.getAsset(buyer.getId(), assetId);

        assertThat(forCreator.isCreator()).isTrue();
        assertThat(forCreator.owned()).isTrue();
        assertThat(forBuyer.isCreator()).isFalse();
        assertThat(forBuyer.owned()).isFalse();

        place(creator, OrderSide.SELL, 50, 1, OrderSource.INVENTORY); // 판매 등록으로 맡기면 보유 아님
        assertThat(query.getAsset(creator.getId(), assetId).owned()).isFalse();
    }

    @Test
    void 제작자가_탈퇴하면_닉네임을_내려주지_않는다() {
        jdbc.update("update users set deleted_at = ? where id = ?", java.sql.Timestamp.from(NOW), creator.getId());
        assertThat(query.getAsset(creator.getId(), assetId).creatorNickname()).isNull();

        jdbc.update("update market_assets set creator_user_id = null where id = ?", assetId);
        assertThat(query.getAsset(creator.getId(), assetId).creatorNickname()).isNull();
        assertThat(cardOf(query.listAssets(creator.getId(), 0, 20)).creatorNickname()).isNull();
    }

    @Test
    void 내_주문은_대기_중과_종료로_나눠_최신순으로_보여준다() {
        User buyer = newUser(null, 100);
        long first = place(buyer, OrderSide.BUY, 10, 1, null);
        engine.runOnce();
        orders.cancel(buyer.getId(), orderIdOf(first), UUID.randomUUID().toString());
        engine.runOnce();
        place(buyer, OrderSide.BUY, 12, 1, null);
        engine.runOnce();

        MyMarketOrderListResponse open = query.myOrders(buyer.getId(), MyMarketOrderStatus.OPEN, 0, 20);
        MyMarketOrderListResponse closed = query.myOrders(buyer.getId(), MyMarketOrderStatus.CLOSED, 0, 20);

        assertThat(open.items()).extracting(MarketOrderResponse::price).containsExactly(12);
        assertThat(closed.items()).singleElement().satisfies(o -> {
            assertThat(o.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(o.name()).isEqualTo("고양이 소파");
        });
        assertThat(query.myOrders(creator.getId(), MyMarketOrderStatus.OPEN, 0, 20).items()).isEmpty();
    }

    @Test
    void 차단한_제작자의_종목은_차단한_사람의_목록에서만_빠지고_상세는_그대로다() {
        User blockedCreator = newUser("철수", 0);
        Long blockedAsset = issue(blockedCreator, 3);
        User viewer = newUser(null, 100);
        jdbc.update("insert into user_blocks (blocker_user_id, blocked_user_id, created_at) values (?, ?, ?)",
                viewer.getId(), blockedCreator.getId(), java.sql.Timestamp.from(NOW));
        try {
            MarketAssetListResponse forViewer = query.listAssets(viewer.getId(), 0, 20);
            assertThat(forViewer.items()).extracting(AssetCard::assetId).containsExactly(assetId);
            assertThat(forViewer.totalElements()).isEqualTo(1);
            // 한 방향: 다른 회원의 목록에는 그대로 나옴
            assertThat(query.listAssets(creator.getId(), 0, 20).items()).extracting(AssetCard::assetId)
                    .containsExactly(blockedAsset, assetId);
            // 상세는 차단과 무관하게 조회됨(보유 가구·대기 주문 화면 유지)
            assertThat(query.getAsset(viewer.getId(), blockedAsset).assetId()).isEqualTo(blockedAsset);
        } finally {
            jdbc.update("delete from user_blocks where blocker_user_id = ?", viewer.getId());
        }
        assertThat(query.listAssets(viewer.getId(), 0, 20).totalElements()).isEqualTo(2);
    }

    @Test
    void 없는_종목은_상세와_체결_모두_찾을_수_없다() {
        assertError(() -> query.getAsset(creator.getId(), 999_999L));
        assertError(() -> query.trades(999_999L, 0, 20));
    }

    private AssetCard cardOf(MarketAssetListResponse list) {
        return list.items().stream().filter(c -> c.assetId().equals(assetId)).findFirst().orElseThrow();
    }

    private Long issue(User owner, int supply) {
        Item item = items.save(new Item(themes.findByCode("photo_furniture").orElseThrow(), "furniture", "positioned",
                null, null, "고양이 소파", null, null, "furniture/photo/" + UUID.randomUUID() + ".png", false, false));
        UserItem copy = userItems.save(UserItem.create(owner, item));
        FurnitureGenerationJob job = FurnitureGenerationJob.create(owner.getId(), UUID.randomUUID().toString(),
                "a".repeat(64), "고양이 소파", NOW, NOW.plus(Duration.ofDays(1)));
        job.succeed(item.getAssetKey(), copy.getId(), NOW);
        jobs.save(job);
        return assetService.issue(owner.getId(), copy.getId(), supply).assetId();
    }

    private long place(User user, OrderSide side, int price, int quantity, OrderSource source) {
        return orders.place(user.getId(), new PlaceOrderRequest(UUID.randomUUID().toString(), assetId, side, price,
                quantity, source)).commandId();
    }

    private long orderIdOf(long commandId) {
        return jdbc.queryForObject("select order_id from market_commands where id = ?", Long.class, commandId);
    }

    private User newUser(String nickname, int coin) {
        User user = users.save(User.signUp("market-query-" + UUID.randomUUID() + "@example.test"));
        if (nickname != null) {
            jdbc.update("update users set nickname = ? where id = ?", nickname, user.getId());
        }
        wallets.save(UserWallet.createWithBalance(user, CurrencyType.COIN, coin));
        return user;
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(MarketErrorCode.ASSET_NOT_FOUND));
    }
}
