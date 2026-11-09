package com.triples.rougether.userapi.gacha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.gacha.entity.Gacha;
import com.triples.rougether.domain.gacha.entity.GachaPoolEntry;
import com.triples.rougether.domain.gacha.entity.GachaRarity;
import com.triples.rougether.domain.gacha.repository.GachaPoolEntryRepository;
import com.triples.rougether.domain.gacha.repository.GachaRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.gacha.dto.GachaDrawRequest;
import com.triples.rougether.userapi.gacha.error.GachaErrorCode;
import com.triples.rougether.userapi.gacha.service.ChristmasGachaDrawPolicy;
import com.triples.rougether.userapi.gacha.service.GachaService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// canonical 풀을 바꾸는 fixture와 실제 커밋/롤백 검증을 다른 테스트 DB에서 격리함.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:mysql:8.4:///christmas_gacha_transactions",
        "christmas.event.enabled=true"
})
@Import(ChristmasGachaTransactionTest.DrawConfiguration.class)
class ChristmasGachaTransactionTest {

    private static final String CHRISTMAS_THEME = "snowy_christmas_202611";
    private static final String ASSET_PREFIX = "items/snowy-christmas-202611/furniture/";

    @Autowired private GachaService service;
    @MockitoSpyBean private GachaRepository machines;
    @Autowired private GachaPoolEntryRepository pools;
    @Autowired private ThemeRepository themes;
    @Autowired private ItemRepository items;
    @MockitoSpyBean private UserRepository users;
    @MockitoSpyBean private UserWalletRepository wallets;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @MockitoSpyBean private UserItemRepository inventory;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> newThemeIds = new ArrayList<>();
    private final List<Long> newItemIds = new ArrayList<>();
    private final List<Long> newEntryIds = new ArrayList<>();
    private final List<ActiveState> itemStates = new ArrayList<>();
    private final List<ActiveState> themeStates = new ArrayList<>();
    private List<ActiveState> machineStates = List.of();
    private List<Long> originalActiveEntries = List.of();
    private Long eventId;
    private Long originalEventThemeId;
    private Long furnitureId;
    private Long normalChristmasItemId;

    @BeforeEach
    void prepareCompleteEventPools() {
        machineStates = jdbc.query("""
                SELECT id,is_active FROM gacha
                WHERE code IN ('wallpaper_gacha','floor_gacha','furniture_gacha','christmas_2026')
                """, (row, index) -> new ActiveState(row.getLong("id"), row.getBoolean("is_active")));
        assertThat(machineStates).hasSize(4);
        eventId = machines.findByCode("christmas_2026").orElseThrow().getId();
        originalEventThemeId = jdbc.queryForObject("SELECT theme_id FROM gacha WHERE id=?", Long.class, eventId);
        furnitureId = machines.findByCode("furniture_gacha").orElseThrow().getId();
        originalActiveEntries = jdbc.queryForList("""
                SELECT p.id FROM gacha_pool_entries p JOIN gacha g ON g.id=p.gacha_id
                WHERE g.code IN ('wallpaper_gacha','floor_gacha','furniture_gacha','christmas_2026')
                  AND p.is_active=TRUE
                """, Long.class);
        jdbc.update("""
                UPDATE gacha_pool_entries p JOIN gacha g ON g.id=p.gacha_id SET p.is_active=FALSE
                WHERE g.code IN ('wallpaper_gacha','floor_gacha','furniture_gacha','christmas_2026')
                """);
        jdbc.update("UPDATE gacha SET is_active=TRUE WHERE id IN (?,?)", eventId, furnitureId);

        Theme christmas = themes.findByCode(CHRISTMAS_THEME).orElseGet(() -> newTheme(CHRISTMAS_THEME));
        themeStates.add(new ActiveState(christmas.getId(), christmas.isActive()));
        jdbc.update("UPDATE themes SET is_active=TRUE WHERE id=?", christmas.getId());
        jdbc.update("UPDATE gacha SET theme_id=? WHERE id=?", christmas.getId(), eventId);
        normalChristmasItemId = seasonalItem(christmas, "snowy-window-animated-v1.webp", GachaRarity.NORMAL).getId();
        seasonalItem(christmas, "plaid-sofa-v1.png", GachaRarity.RARE);
        seasonalItem(christmas, "christmas-tree-animated-v1.webp", GachaRarity.LEGENDARY);
        Theme regular = newTheme("christmas_tx_" + UUID.randomUUID());
        for (String rarity : List.of(GachaRarity.NORMAL, GachaRarity.RARE, GachaRarity.LEGENDARY)) {
            Item item = newItem(regular, "items/christmas-tx/" + UUID.randomUUID() + ".png");
            entry(furnitureId, item, rarity);
        }
    }

    @AfterEach
    void restoreSeedAndRemoveFixtures() {
        for (Long userId : userIds) {
            jdbc.update("DELETE FROM wallet_histories WHERE user_id=?", userId);
            jdbc.update("DELETE FROM user_items WHERE user_id=?", userId);
            jdbc.update("DELETE FROM user_wallets WHERE user_id=?", userId);
            jdbc.update("DELETE FROM users WHERE id=?", userId);
        }
        newEntryIds.forEach(id -> jdbc.update("DELETE FROM gacha_pool_entries WHERE id=?", id));
        newItemIds.forEach(id -> jdbc.update("DELETE FROM items WHERE id=?", id));
        itemStates.forEach(state -> jdbc.update("UPDATE items SET is_active=? WHERE id=?", state.active(), state.id()));
        themeStates.forEach(state -> jdbc.update("UPDATE themes SET is_active=? WHERE id=?", state.active(), state.id()));
        if (eventId != null) {
            jdbc.update("UPDATE gacha SET theme_id=? WHERE id=?", originalEventThemeId, eventId);
        }
        newThemeIds.forEach(id -> jdbc.update("DELETE FROM themes WHERE id=?", id));
        originalActiveEntries.forEach(id -> jdbc.update("UPDATE gacha_pool_entries SET is_active=TRUE WHERE id=?", id));
        machineStates.forEach(state -> jdbc.update("UPDATE gacha SET is_active=? WHERE id=?", state.active(), state.id()));
    }

    @Test
    void 같은_회원이_25코인으로_동시에_두번_뽑으면_한번만_차감하고_지급한다() throws Exception {
        User user = fundedUser();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<GachaErrorCode> outcomes = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> drawWhenReleased(user, ready, start));
            var second = executor.submit(() -> drawWhenReleased(user, ready, start));
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            outcomes.add(first.get(30, TimeUnit.SECONDS));
            outcomes.add(second.get(30, TimeUnit.SECONDS));
        }

        assertThat(outcomes).containsExactlyInAnyOrder(null, GachaErrorCode.INSUFFICIENT_COIN);
        assertThat(balance(user)).isZero();
        assertThat(inventory.findOwnedItemIdsByUserId(user.getId())).containsExactly(normalChristmasItemId);
        assertThat(rowCount("user_items", user)).isEqualTo(1);
        assertThat(rowCount("wallet_histories", user)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT amount,balance_after,source_id FROM wallet_histories WHERE user_id=?", user.getId()))
                .containsEntry("amount", -25).containsEntry("balance_after", 0).containsEntry("source_id", eventId);
    }

    @Test
    void 같은_회원이_50코인으로_동시에_같은_가구를_뽑으면_두번째는_중복_다이아로_전환한다() throws Exception {
        User user = fundedUser(50);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> drawWhenReleased(user, ready, start));
            var second = executor.submit(() -> drawWhenReleased(user, ready, start));
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            assertThat(first.get(30, TimeUnit.SECONDS)).isNull();
            assertThat(second.get(30, TimeUnit.SECONDS)).isNull();
        }

        assertThat(balance(user)).isZero();
        assertThat(jdbc.queryForObject("SELECT balance FROM user_wallets WHERE user_id=? AND currency_type='DIAMOND'",
                Integer.class, user.getId())).isEqualTo(3);
        assertThat(inventory.findOwnedItemIdsByUserId(user.getId())).containsExactly(normalChristmasItemId);
        assertThat(rowCount("user_items", user)).isEqualTo(1);
        assertThat(rowCount("wallet_histories", user)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wallet_histories
                WHERE user_id=? AND reason='GACHA_DRAW' AND currency_type='COIN'
                  AND amount=-25 AND source_type='GACHA' AND source_id=?
                """, Integer.class, user.getId(), eventId)).isEqualTo(2);
        assertThat(jdbc.queryForMap("""
                SELECT amount,balance_after,source_id FROM wallet_histories
                WHERE user_id=? AND reason='GACHA_DUPLICATE_CONVERT' AND currency_type='DIAMOND'
                """, user.getId()))
                .containsEntry("amount", 3).containsEntry("balance_after", 3).containsEntry("source_id", eventId);
    }

    @Test
    void 차감과_지급_SQL_후_인벤토리_저장이_실패하면_지갑과_원장도_함께_롤백된다() {
        User user = fundedUser();
        doAnswer(invocation -> {
            entityManager.persist(invocation.getArgument(0, UserItem.class));
            entityManager.flush();
            assertThat(balance(user)).isZero();
            assertThat(rowCount("wallet_histories", user)).isEqualTo(1);
            assertThat(rowCount("user_items", user)).isEqualTo(1);
            throw new IllegalStateException("크리스마스 인벤토리 저장 후 실패");
        }).when(inventory).save(any(UserItem.class));

        assertThatThrownBy(() -> service.draw(user.getId(), eventId, new GachaDrawRequest(1)))
                .isInstanceOf(IllegalStateException.class).hasMessage("크리스마스 인벤토리 저장 후 실패");

        // 일반 SELECT가 RR snapshot을 만들기 전에 사용자와 지갑 잠금을 먼저 획득해야 함.
        var reads = inOrder(users, wallets, machines);
        reads.verify(users).findByIdForUpdate(user.getId());
        reads.verify(wallets).findWithLockByUserIdAndCurrencyType(user.getId(), CurrencyType.COIN);
        reads.verify(machines).findById(eventId);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(balance(user)).isEqualTo(25);
        assertThat(rowCount("wallet_histories", user)).isZero();
        assertThat(rowCount("user_items", user)).isZero();
    }

    private GachaErrorCode drawWhenReleased(User user, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            var result = service.draw(user.getId(), eventId, new GachaDrawRequest(1));
            assertThat(result.results()).singleElement().satisfies(reward ->
                    assertThat(reward.itemId()).isEqualTo(normalChristmasItemId));
            return null;
        } catch (BusinessException failure) {
            return (GachaErrorCode) failure.getErrorCode();
        }
    }

    private User fundedUser() {
        return fundedUser(25);
    }

    private User fundedUser(int balance) {
        User user = users.save(User.signUp());
        userIds.add(user.getId());
        wallets.save(UserWallet.createWithBalance(user, CurrencyType.COIN, balance));
        return user;
    }

    private Theme newTheme(String code) {
        Theme theme = themes.save(new Theme(code, "트랜잭션 테스트", null, true));
        newThemeIds.add(theme.getId());
        return theme;
    }

    private Item newItem(Theme theme, String assetKey) {
        Item item = items.save(new Item(theme, "furniture", "positioned", null, null, "가구",
                null, null, assetKey, true, true));
        newItemIds.add(item.getId());
        return item;
    }

    private Item seasonalItem(Theme theme, String assetName, String rarity) {
        Item item = items.findByAssetKey(ASSET_PREFIX + assetName)
                .orElseGet(() -> newItem(theme, ASSET_PREFIX + assetName));
        itemStates.add(new ActiveState(item.getId(), item.isActive()));
        jdbc.update("UPDATE items SET is_active=TRUE WHERE id=?", item.getId());
        entry(eventId, item, rarity);
        return item;
    }

    private void entry(Long machineId, Item item, String rarity) {
        Gacha machine = machines.getReferenceById(machineId);
        newEntryIds.add(pools.save(GachaPoolEntry.itemEntry(machine, item, rarity)).getId());
    }

    private int balance(User user) {
        return jdbc.queryForObject("SELECT balance FROM user_wallets WHERE user_id=? AND currency_type='COIN'",
                Integer.class, user.getId());
    }

    private int rowCount(String table, User user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Integer.class, user.getId());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DrawConfiguration {
        @Bean
        @Primary
        Clock christmasTransactionClock() {
            return Clock.fixed(Instant.parse("2026-11-09T03:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        ChristmasGachaDrawPolicy christmasTransactionDrawPolicy() {
            return new ChristmasGachaDrawPolicy(new Random() {
                @Override
                public int nextInt(int bound) {
                    return 0;
                }
            });
        }
    }

    private record ActiveState(Long id, boolean active) {
    }
}
