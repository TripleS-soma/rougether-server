package com.triples.rougether.userapi.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob;
import com.triples.rougether.domain.furniture.repository.FurnitureGenerationJobRepository;
import com.triples.rougether.domain.market.entity.MarketAsset;
import com.triples.rougether.domain.market.entity.MarketAssetStatus;
import com.triples.rougether.domain.market.repository.MarketAssetRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.market.dto.MarketAssetResponse;
import com.triples.rougether.userapi.market.error.MarketErrorCode;
import com.triples.rougether.userapi.market.service.MarketAssetService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 거래소 발행과 user_items 활성 유니크 재설계(#406)를 실제 MySQL 스키마(V80)에서 검증함.
@SpringBootTest
class MarketAssetIssueIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @Autowired private MarketAssetService marketAssetService;
    @Autowired private MarketAssetRepository marketAssets;
    @Autowired private UserRepository users;
    @Autowired private ThemeRepository themes;
    @Autowired private ItemRepository items;
    @Autowired private UserItemRepository userItems;
    @Autowired private FurnitureGenerationJobRepository jobs;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private Clock kstClock;

    private User creator;
    private Item photoItem;
    private UserItem creatorCopy;
    private FurnitureGenerationJob job;

    @BeforeEach
    void setUp() {
        when(kstClock.instant()).thenReturn(NOW);
        when(kstClock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        creator = newUser();
        photoItem = newItem(themes.findByCode("photo_furniture").orElseThrow());
        creatorCopy = userItems.save(UserItem.create(creator, photoItem));
        job = FurnitureGenerationJob.create(creator.getId(), UUID.randomUUID().toString(),
                "a".repeat(64), "고양이 소파", NOW, NOW.plus(Duration.ofDays(1)));
        job.succeed(photoItem.getAssetKey(), creatorCopy.getId(), NOW);
        jobs.save(job);
    }

    @AfterEach
    void cleanUp() {
        // 다른 통합 테스트의 items 정리가 FK 에 막히지 않도록 이 테스트가 만든 종목을 지움
        jdbc.update("delete from market_assets");
    }

    @Test
    void 제작자가_발행하면_보유분_1개를_뺀_나머지가_발행_재고가_된다() {
        MarketAssetResponse response = marketAssetService.issue(creator.getId(), creatorCopy.getId(), 5);

        MarketAsset saved = marketAssets.findById(response.assetId()).orElseThrow();
        assertThat(saved.getItemId()).isEqualTo(photoItem.getId());
        assertThat(saved.getCreatorUserId()).isEqualTo(creator.getId());
        assertThat(saved.getTotalSupply()).isEqualTo(5);
        assertThat(saved.getUnissuedQuantity()).isEqualTo(4);
        assertThat(saved.getStatus()).isEqualTo(MarketAssetStatus.ACTIVE);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(response.name()).isEqualTo("고양이 소파");
        assertThat(response.assetKey()).isEqualTo(photoItem.getAssetKey());
        assertThat(response.creatorNickname()).isEqualTo(creator.getNickname());
        assertThat(response.isCreator()).isTrue();
        assertThat(response.owned()).isTrue();
        assertThat(response.unissuedQuantity()).isEqualTo(4);
        assertThat(response.asks()).isEmpty();
        assertThat(response.bids()).isEmpty();
        assertThat(response.lastTradePrice()).isNull();
    }

    @Test
    void 한_개만_발행하면_발행_재고는_0이다() {
        MarketAssetResponse response = marketAssetService.issue(creator.getId(), creatorCopy.getId(), 1);

        assertThat(response.unissuedQuantity()).isZero();
    }

    @Test
    void 최대_10개까지_발행할_수_있다() {
        MarketAssetResponse response = marketAssetService.issue(creator.getId(), creatorCopy.getId(), 10);

        assertThat(response.totalSupply()).isEqualTo(10);
        assertThat(response.unissuedQuantity()).isEqualTo(9);
    }

    @Test
    void 같은_가구를_보유했더라도_제작자가_아니면_발행할_수_없다() {
        User buyer = newUser();
        UserItem buyerCopy = userItems.save(UserItem.create(buyer, photoItem));

        assertError(() -> marketAssetService.issue(buyer.getId(), buyerCopy.getId(), 3), MarketErrorCode.NOT_CREATOR);
        assertThat(marketAssets.existsByItemId(photoItem.getId())).isFalse();
    }

    @Test
    void 남의_보유_아이템이나_맡긴_아이템으로는_발행할_수_없다() {
        User other = newUser();
        assertError(() -> marketAssetService.issue(other.getId(), creatorCopy.getId(), 3), MarketErrorCode.ITEM_NOT_OWNED);

        creatorCopy.deactivate(NOW);
        userItems.saveAndFlush(creatorCopy);
        assertError(() -> marketAssetService.issue(creator.getId(), creatorCopy.getId(), 3), MarketErrorCode.ITEM_NOT_OWNED);
    }

    @Test
    void 사진으로_만든_가구가_아니면_발행할_수_없다() {
        Theme otherTheme = themes.save(new Theme("market-test-" + UUID.randomUUID(), "상점 테마", null, true));
        UserItem shopItem = userItems.save(UserItem.create(creator, newItem(otherTheme)));

        assertError(() -> marketAssetService.issue(creator.getId(), shopItem.getId(), 3), MarketErrorCode.ITEM_NOT_TRADABLE);
    }

    @Test
    void 같은_가구는_한_번만_발행할_수_있다() {
        marketAssetService.issue(creator.getId(), creatorCopy.getId(), 3);

        assertError(() -> marketAssetService.issue(creator.getId(), creatorCopy.getId(), 3), MarketErrorCode.ASSET_ALREADY_LISTED);
        assertThat(marketAssets.count()).isEqualTo(1);
    }

    @Test
    void 재검수가_진행_중인_가구는_발행할_수_없다() {
        job.requestReview("다리가 이상해요", NOW);
        jobs.save(job);

        assertError(() -> marketAssetService.issue(creator.getId(), creatorCopy.getId(), 3),
                MarketErrorCode.ITEM_REVIEW_IN_PROGRESS);
        assertThat(marketAssets.existsByItemId(photoItem.getId())).isFalse();
    }

    @Test
    void 재검수가_실패해도_가구는_남으므로_발행할_수_있다() {
        job.requestReview("다리가 이상해요", NOW);
        job.fail("REVIEW_REJECTED", NOW);
        jobs.save(job);

        MarketAssetResponse response = marketAssetService.issue(creator.getId(), creatorCopy.getId(), 3);

        assertThat(response.totalSupply()).isEqualTo(3);
    }

    @Test
    void 활성_보유는_사람당_아이템_1개만_허용된다() {
        assertThatThrownBy(() -> userItems.saveAndFlush(UserItem.create(creator, photoItem)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 맡겨서_비활성이_된_이력이_있어도_같은_아이템을_다시_얻을_수_있다() {
        creatorCopy.deactivate(NOW);
        userItems.saveAndFlush(creatorCopy);
        UserItem second = userItems.saveAndFlush(UserItem.create(creator, photoItem));
        second.deactivate(NOW);
        userItems.saveAndFlush(second);

        UserItem third = userItems.saveAndFlush(UserItem.create(creator, photoItem));

        assertThat(userItems.findByUserIdAndItemIdAndDeletedAtIsNull(creator.getId(), photoItem.getId()))
                .get().extracting(UserItem::getId).isEqualTo(third.getId());
    }

    @Test
    void 이미_활성_보유가_있으면_맡긴_아이템을_되살릴_수_없다() {
        creatorCopy.deactivate(NOW);
        userItems.saveAndFlush(creatorCopy);
        userItems.saveAndFlush(UserItem.create(creator, photoItem));

        UserItem reloaded = userItems.findById(creatorCopy.getId()).orElseThrow();
        reloaded.reactivate();
        assertThatThrownBy(() -> userItems.saveAndFlush(reloaded))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private User newUser() {
        return users.save(User.signUp("market-" + UUID.randomUUID() + "@example.test"));
    }

    private Item newItem(Theme theme) {
        return items.save(new Item(theme, "furniture", "positioned", null, null, "고양이 소파",
                null, null, "furniture/photo/" + UUID.randomUUID() + ".png", false, false));
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, MarketErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
