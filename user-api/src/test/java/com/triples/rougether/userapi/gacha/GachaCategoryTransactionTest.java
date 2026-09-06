package com.triples.rougether.userapi.gacha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.triples.rougether.domain.gacha.entity.Gacha;
import com.triples.rougether.domain.gacha.entity.GachaCategory;
import com.triples.rougether.domain.gacha.entity.GachaPoolEntry;
import com.triples.rougether.domain.gacha.entity.GachaRarity;
import com.triples.rougether.domain.gacha.repository.GachaPoolEntryRepository;
import com.triples.rougether.domain.gacha.repository.GachaRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.entity.WalletHistory;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.member.repository.WalletHistoryRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.gacha.dto.GachaDrawRequest;
import com.triples.rougether.userapi.gacha.dto.GachaDrawResponse;
import com.triples.rougether.userapi.gacha.service.GachaService;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 서비스의 실제 커밋/롤백을 확인하므로 테스트 자체에는 @Transactional 을 사용하지 않는다.
// canonical seed 풀을 잠시 비활성화하는 동안 다른 테스트에 영향을 주지 않도록 DB 를 분리한다.
@SpringBootTest(properties = "spring.datasource.url=jdbc:tc:mysql:8.4:///gacha_category_transactions")
class GachaCategoryTransactionTest {

    @Autowired private GachaService gachaService;
    @Autowired private GachaRepository gachaRepository;
    @Autowired private GachaPoolEntryRepository poolRepository;
    @Autowired private ThemeRepository themeRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserWalletRepository walletRepository;
    @Autowired private WalletHistoryRepository historyRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @MockitoSpyBean private UserItemRepository userItemRepository;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> themeIds = new ArrayList<>();
    private final List<Long> itemIds = new ArrayList<>();
    private final List<Long> entryIds = new ArrayList<>();
    private List<Long> activeSeedEntryIds = List.of();
    private Gacha furnitureGacha;
    private Random originalRandom;

    @BeforeEach
    void isolateCanonicalPool() {
        furnitureGacha = gachaRepository.findAllWithTheme().stream()
                .filter(gacha -> gacha.getCategory() == GachaCategory.FURNITURE)
                .findFirst().orElseThrow();
        activeSeedEntryIds = jdbcTemplate.queryForList(
                "select id from gacha_pool_entries where gacha_id = ? and is_active = true",
                Long.class, furnitureGacha.getId());
        jdbcTemplate.update("update gacha_pool_entries set is_active = false where gacha_id = ?",
                furnitureGacha.getId());
        originalRandom = (Random) ReflectionTestUtils.getField(gachaService, "random");
        // 전설 경계값을 선택한다. 잘못 등록한 전설 보상을 필터하지 않으면 반드시 테스트가 실패한다.
        ReflectionTestUtils.setField(gachaService, "random", new Random() {
            @Override
            public int nextInt(int bound) {
                return bound - 1;
            }
        });
    }

    @AfterEach
    void restoreSeedAndRemoveFixtures() {
        if (originalRandom != null) {
            ReflectionTestUtils.setField(gachaService, "random", originalRandom);
        }
        for (Long userId : userIds) {
            jdbcTemplate.update("delete from wallet_histories where user_id = ?", userId);
            jdbcTemplate.update("delete from user_items where user_id = ?", userId);
            jdbcTemplate.update("delete from user_wallets where user_id = ?", userId);
            jdbcTemplate.update("delete from users where id = ?", userId);
        }
        entryIds.forEach(id -> jdbcTemplate.update("delete from gacha_pool_entries where id = ?", id));
        itemIds.forEach(id -> jdbcTemplate.update("delete from items where id = ?", id));
        themeIds.forEach(id -> jdbcTemplate.update("delete from themes where id = ?", id));
        activeSeedEntryIds.forEach(id -> jdbcTemplate.update(
                "update gacha_pool_entries set is_active = true where id = ?", id));
    }

    @Test
    void 가구_박스는_여러_테마를_통합하고_잘못_섞인_벽지_바닥_악세사리를_노출하거나_지급하지_않는다() {
        User user = fundedUser();
        Theme forest = theme("숲");
        Theme ocean = theme("바다");
        Item chair = item(forest, "furniture", "positioned", null, "의자");
        Item rug = item(ocean, "rug", "positioned", null, "러그");
        GachaPoolEntry chairEntry = entry(chair, GachaRarity.NORMAL);
        GachaPoolEntry rugEntry = entry(rug, GachaRarity.NORMAL);
        entry(item(forest, "wallpaper", "surface_slot", "wallpaper", "벽지"), GachaRarity.LEGENDARY);
        entry(item(ocean, "floor", "surface_slot", "floor", "바닥"), GachaRarity.LEGENDARY);
        entry(item(ocean, "character_accessory", "positioned", null, "잘못 등록한 안경"),
                GachaRarity.LEGENDARY);

        assertThat(gachaService.getGachaList().items()).extracting(response -> response.category())
                .containsExactly(GachaCategory.WALLPAPER, GachaCategory.FLOOR, GachaCategory.FURNITURE);
        assertThat(gachaService.getRewards(user.getId(), furnitureGacha.getId()).items())
                .extracting(reward -> reward.itemId()).containsExactly(chair.getId(), rug.getId());

        // 각 테마 보상이 실제 지급 가능한지 확인한다. 잘못 등록한 전설 엔트리는 계속 활성 상태다.
        setActive(rugEntry, false);
        assertThat(draw(user, 1).results()).singleElement().satisfies(result -> {
            assertThat(result.itemId()).isEqualTo(chair.getId());
            assertThat(result.rewardType()).isEqualTo("ITEM");
        });
        setActive(chairEntry, false);
        setActive(rugEntry, true);
        assertThat(draw(user, 1).results()).singleElement().satisfies(result -> {
            assertThat(result.itemId()).isEqualTo(rug.getId());
            assertThat(result.rewardType()).isEqualTo("ITEM");
        });
        assertThat(userItemRepository.findOwnedItemIdsByUserId(user.getId()))
                .containsExactlyInAnyOrder(chair.getId(), rug.getId());
        assertThat(balance(user, CurrencyType.COIN)).isEqualTo(950);
    }

    @ParameterizedTest
    @CsvSource({"1,false,1,25,0", "1,true,1,25,3", "6,false,6,125,15", "10,false,6,125,15"})
    void 단챠와_신구_연속뽑기의_비용_중복환급_원장이_커밋된다(
            int requestCount, boolean alreadyOwned, int resultCount, int cost, int refund) {
        User user = fundedUser();
        Item chair = item(theme("가구"), "furniture", "positioned", null, "의자");
        entry(chair, GachaRarity.NORMAL);
        if (alreadyOwned) {
            userItemRepository.save(UserItem.create(user, chair));
        }

        GachaDrawResponse response = draw(user, requestCount);

        assertThat(response.results()).hasSize(resultCount).allSatisfy(result -> {
            assertThat(result.itemId()).isEqualTo(chair.getId());
            if (result.converted()) {
                assertThat(result.rewardType()).isEqualTo("CURRENCY");
                assertThat(result.refundCurrencyType()).isEqualTo(CurrencyType.DIAMOND);
                assertThat(result.refundAmount()).isEqualTo(3);
            } else {
                assertThat(result.rewardType()).isEqualTo("ITEM");
            }
        });
        assertThat(response.results().stream().filter(result -> result.converted())).hasSize(refund / 3);
        assertThat(userItemRepository.findOwnedItemIdsByUserId(user.getId())).containsExactly(chair.getId());
        assertThat(balance(user, CurrencyType.COIN)).isEqualTo(1000 - cost);
        assertThat(balance(user, CurrencyType.DIAMOND)).isEqualTo(refund);
        assertThat(response.wallets()).extracting(wallet -> wallet.currencyType(), wallet -> wallet.balance())
                .containsExactly(tuple(CurrencyType.COIN, 1000 - cost), tuple(CurrencyType.DIAMOND, refund));

        List<WalletHistory> histories = historyRepository
                .findHistories(user.getId(), null, null, PageRequest.of(0, 10)).getContent();
        assertThat(histories).hasSize(refund == 0 ? 1 : 2).allSatisfy(history -> {
            assertThat(history.getSourceType()).isEqualTo(WalletHistory.SOURCE_GACHA);
            assertThat(history.getSourceId()).isEqualTo(furnitureGacha.getId());
        });
        assertThat(histories).filteredOn(history -> history.getReason() == WalletHistoryReason.GACHA_DRAW)
                .extracting(WalletHistory::getCurrencyType, WalletHistory::getAmount, WalletHistory::getBalanceAfter)
                .containsExactly(tuple(CurrencyType.COIN, -cost, 1000 - cost));
        if (refund > 0) {
            assertThat(histories)
                    .filteredOn(history -> history.getReason() == WalletHistoryReason.GACHA_DUPLICATE_CONVERT)
                    .extracting(WalletHistory::getCurrencyType, WalletHistory::getAmount, WalletHistory::getBalanceAfter)
                    .containsExactly(tuple(CurrencyType.DIAMOND, refund, refund));
        }
    }

    @Test
    void 코인과_원장_인벤토리가_DB에_반영된_뒤_지급이_실패하면_전체가_롤백된다() {
        User user = fundedUser();
        entry(item(theme("롤백"), "furniture", "positioned", null, "의자"), GachaRarity.NORMAL);
        doAnswer(invocation -> {
            entityManager.persist(invocation.getArgument(0, UserItem.class));
            entityManager.flush();
            // 실패 직전 실제 SQL 쓰기가 수행되었음을 같은 트랜잭션에서 확인한다.
            assertThat(balance(user, CurrencyType.COIN)).isEqualTo(975);
            assertThat(rowCount("wallet_histories", user)).isEqualTo(1);
            assertThat(rowCount("user_items", user)).isEqualTo(1);
            throw new IllegalStateException("인벤토리 저장 후 강제 실패");
        }).when(userItemRepository).save(any(UserItem.class));

        assertThatThrownBy(() -> draw(user, 1)).isInstanceOf(IllegalStateException.class)
                .hasMessage("인벤토리 저장 후 강제 실패");

        // 서비스 트랜잭션 종료 후 새 JDBC 조회로 영속 결과를 확인한다(영속성 컨텍스트 재사용 없음).
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(balance(user, CurrencyType.COIN)).isEqualTo(1000);
        assertThat(balance(user, CurrencyType.DIAMOND)).isZero();
        assertThat(rowCount("wallet_histories", user)).isZero();
        assertThat(rowCount("user_items", user)).isZero();
    }

    private User fundedUser() {
        User user = userRepository.save(User.signUp());
        userIds.add(user.getId());
        walletRepository.save(UserWallet.createWithBalance(user, CurrencyType.COIN, 1000));
        walletRepository.save(UserWallet.create(user, CurrencyType.DIAMOND));
        return user;
    }

    private Theme theme(String name) {
        Theme theme = themeRepository.save(new Theme("gacha_tx_" + UUID.randomUUID(), name, null, true));
        themeIds.add(theme.getId());
        return theme;
    }

    private Item item(Theme theme, String category, String placement, String slot, String name) {
        Item item = itemRepository.save(new Item(theme, category, placement, slot, null,
                name, null, null, "items/gacha-tx/" + UUID.randomUUID() + ".png", false, true));
        itemIds.add(item.getId());
        return item;
    }

    private GachaPoolEntry entry(Item item, String rarity) {
        GachaPoolEntry entry = poolRepository.save(GachaPoolEntry.itemEntry(furnitureGacha, item, rarity));
        entryIds.add(entry.getId());
        return entry;
    }

    private void setActive(GachaPoolEntry entry, boolean active) {
        jdbcTemplate.update("update gacha_pool_entries set is_active = ? where id = ?", active, entry.getId());
    }

    private GachaDrawResponse draw(User user, int count) {
        return gachaService.draw(user.getId(), furnitureGacha.getId(), new GachaDrawRequest(count));
    }

    private int balance(User user, CurrencyType currency) {
        return jdbcTemplate.queryForObject(
                "select balance from user_wallets where user_id = ? and currency_type = ?",
                Integer.class, user.getId(), currency.name());
    }

    private int rowCount(String table, User user) {
        return jdbcTemplate.queryForObject("select count(*) from " + table + " where user_id = ?",
                Integer.class, user.getId());
    }
}
