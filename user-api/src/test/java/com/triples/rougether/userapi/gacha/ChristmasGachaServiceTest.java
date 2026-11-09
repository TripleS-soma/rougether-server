package com.triples.rougether.userapi.gacha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
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
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.character.service.CharacterUnlockPolicy;
import com.triples.rougether.userapi.christmas.service.ChristmasEventPolicy;
import com.triples.rougether.userapi.gacha.dto.GachaDrawRequest;
import com.triples.rougether.userapi.gacha.error.GachaErrorCode;
import com.triples.rougether.userapi.gacha.service.ChristmasGachaDrawPolicy;
import com.triples.rougether.userapi.gacha.service.GachaDrawPolicy;
import com.triples.rougether.userapi.gacha.service.GachaService;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class ChristmasGachaServiceTest {
    private static final Instant DURING_EVENT = Instant.parse("2026-11-09T03:00:00Z");
    private static final String ASSET_PREFIX = "items/snowy-christmas-202611/";
    private final GachaRepository machines = mock(GachaRepository.class);
    private final GachaPoolEntryRepository pools = mock(GachaPoolEntryRepository.class);
    private final UserItemRepository inventory = mock(UserItemRepository.class);
    private final UserCharacterRepository characters = mock(UserCharacterRepository.class);
    private final UserWalletRepository wallets = mock(UserWalletRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final WalletHistoryRecorder history = mock(WalletHistoryRecorder.class);
    private final CharacterUnlockPolicy unlock = mock(CharacterUnlockPolicy.class);
    private final Theme christmasTheme = new Theme("snowy_christmas_202611", "크리스마스", null, true);
    private final Theme ordinaryTheme = new Theme("christmas-test-ordinary", "기존 테마", null, true);
    private final User user = User.signUp();
    private final UserWallet coin = UserWallet.createWithBalance(user, CurrencyType.COIN, 1_000);
    private final UserWallet diamond = UserWallet.create(user, CurrencyType.DIAMOND);
    private Gacha christmas;
    private Gacha furniture;
    private List<GachaPoolEntry> christmasEntries;
    private List<GachaPoolEntry> regularEntries;
    private long nextItemId;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(user, "id", 1L);
        nextItemId = 100;
        christmas = machine(90L, "christmas_2026", christmasTheme);
        furniture = machine(3L, "furniture_gacha", null);
        christmasEntries = christmasEntries();
        regularEntries = new ArrayList<>(List.of(
                entry(furniture, ordinaryTheme, "items/regular/chair.png", GachaRarity.NORMAL),
                entry(furniture, ordinaryTheme, "items/regular/table.png", GachaRarity.RARE),
                entry(furniture, ordinaryTheme, "items/regular/sofa.png", GachaRarity.LEGENDARY)));
        when(machines.findById(90L)).thenReturn(Optional.of(christmas));
        when(machines.findAllWithTheme()).thenReturn(List.of(christmas, furniture));
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(users.getReferenceById(1L)).thenReturn(user);
        when(wallets.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.COIN)).thenReturn(Optional.of(coin));
        when(wallets.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.DIAMOND)).thenReturn(Optional.of(diamond));
        when(inventory.findByUserIdAndDeletedAtIsNull(1L)).thenReturn(List.of());
        when(inventory.findOwnedItemIdsByUserId(1L)).thenReturn(List.of());
        when(characters.findByUserId(1L)).thenReturn(List.of());
        when(characters.findOwnedCharacterIdsByUserId(1L)).thenReturn(List.of());
        when(pools.findAttendanceRewardItemIds()).thenReturn(List.of());
        stubPools();
    }

    @Test
    void 기본OFF이면_목록에서_숨기고_상세도_비활성이며_뽑기와_확률조회를_차단한다() {
        GachaService service = service(false, DURING_EVENT, () -> 0L);
        assertThat(service.getGachaList().items()).isEmpty();
        assertThat(service.getGacha(90L).active()).isFalse();
        assertInactive(() -> service.draw(1L, 90L, new GachaDrawRequest(1)));
        assertInactive(() -> service.getRewards(1L, 90L));
        assertNoPaymentOrGrant();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-08T14:59:59.999999Z", "2026-12-25T15:00:00Z"})
    void 실제_KST기간_밖이면_DB머신이_활성이어도_차감과_지급을_하지_않는다(String instant) {
        GachaService service = service(true, Instant.parse(instant), () -> 0L);
        assertThat(service.getGachaList().items()).isEmpty();
        assertThat(service.getGacha(90L).active()).isFalse();
        assertInactive(() -> service.draw(1L, 90L, new GachaDrawRequest(6)));
        assertNoPaymentOrGrant();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-08T15:00:00Z", "2026-12-25T14:59:59.999999Z"})
    void 시작과_마지막_저장정밀도_경계는_동일한_머신으로_노출한다(String instant) {
        GachaService service = service(true, Instant.parse(instant), () -> 0L);
        assertThat(service.getGachaList().items()).extracting(response -> response.gachaId()).containsExactly(90L);
        assertThat(service.getGacha(90L).active()).isTrue();
        assertThat(service.getRewards(1L, 90L).items()).hasSize(13);
        assertNoPaymentOrGrant();
    }

    @Test
    void 지갑잠금_대기중_기간이_끝나면_잠금획득후_다시_확인하여_차감하지_않는다() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-12-25T14:59:59.999999Z"));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(ignored -> now.get());
        when(wallets.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.COIN)).thenAnswer(ignored -> {
            now.set(Instant.parse("2026-12-25T15:00:00Z"));
            return Optional.of(coin);
        });
        GachaService service = service(true, clock, () -> 0L);
        assertInactive(() -> service.draw(1L, 90L, new GachaDrawRequest(1)));
        verify(users).findByIdForUpdate(1L);
        verify(wallets).findWithLockByUserIdAndCurrencyType(1L, CurrencyType.COIN);
        verify(clock).instant();
        assertNoPaymentOrGrant();
    }

    @ParameterizedTest
    @ValueSource(strings = {"furniture_gacha", "legacy_theme_machine"})
    void 일반_카테고리와_옛테마_머신에_크리스마스가_잘못_등록되어도_노출하거나_지급하지_않는다(String code) {
        Gacha regular = machine(7L, code, ordinaryTheme);
        when(machines.findById(7L)).thenReturn(Optional.of(regular));
        GachaPoolEntry ordinary = regularEntries.getFirst();
        List<GachaPoolEntry> leaked = List.of(christmasEntries.getFirst(), ordinary);
        when(pools.findActiveRewardsByGachaId(7L)).thenReturn(leaked);
        when(pools.findByGachaIdAndActiveIsTrue(7L)).thenReturn(leaked);
        GachaService service = service(true, DURING_EVENT, () -> 0L);

        assertThat(service.getRewards(1L, 7L).items()).extracting(reward -> reward.itemId())
                .containsExactly(ordinary.getItem().getId());
        var result = service.draw(1L, 7L, new GachaDrawRequest(1));
        assertThat(result.results()).extracting(draw -> draw.itemId()).containsExactly(ordinary.getItem().getId());
        assertThat(coin.getBalance()).isEqualTo(975);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 양수확률_등급이_비면_어느그룹으로도_대체하지_않고_차감전에_거절한다(boolean christmasGroup) {
        (christmasGroup ? christmasEntries : regularEntries)
                .removeIf(entry -> GachaRarity.LEGENDARY.equals(entry.getRarity()));
        stubPools();
        GachaService service = service(true, DURING_EVENT, () -> 0L);

        assertThatThrownBy(() -> service.draw(1L, 90L, new GachaDrawRequest(6)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(GachaErrorCode.EMPTY_POOL));
        assertNoPaymentOrGrant();
    }

    @Test
    void 여섯_독립결과는_125코인이며_크리스마스_중복도_개당_다이아3을_지급한다() {
        // 그룹, 등급, 해당 등급 안 인덱스: 전부 크리스마스 일반 첫 아이템을 선택한다.
        GachaService service = service(true, DURING_EVENT, () -> 0L);
        var result = service.draw(1L, 90L, new GachaDrawRequest(6));

        assertThat(result.results()).hasSize(6);
        assertThat(result.results()).extracting(draw -> draw.rewardType())
                .containsExactly("ITEM", "CURRENCY", "CURRENCY", "CURRENCY", "CURRENCY", "CURRENCY");
        assertThat(result.results()).extracting(draw -> draw.itemId())
                .containsOnly(christmasEntries.getFirst().getItem().getId());
        assertThat(result.results().subList(1, 6)).allSatisfy(draw -> {
            assertThat(draw.refundCurrencyType()).isEqualTo(CurrencyType.DIAMOND);
            assertThat(draw.refundAmount()).isEqualTo(3);
            assertThat(draw.converted()).isTrue();
        });
        assertThat(coin.getBalance()).isEqualTo(875);
        assertThat(diamond.getBalance()).isEqualTo(15);
        verify(inventory).save(any(UserItem.class));
        verify(characters, never()).save(any());
    }

    @Test
    void 일반그룹은_itemId중복과_다른_출석전용보상을_제외하고_트리는_크리스마스_보상에_포함한다() {
        regularEntries.add(regularEntries.getFirst());
        GachaPoolEntry attendanceOnly = entry(furniture, ordinaryTheme, "items/regular/attendance-only.png", GachaRarity.NORMAL);
        regularEntries.add(attendanceOnly);
        when(pools.findAttendanceRewardItemIds()).thenReturn(List.of(attendanceOnly.getItem().getId()));
        GachaPoolEntry unknown = entry(christmas, christmasTheme,
                ASSET_PREFIX + "furniture/unapproved.png", GachaRarity.LEGENDARY);
        christmasEntries.add(unknown);
        stubPools();
        var rewards = service(true, DURING_EVENT, () -> 0L).getRewards(1L, 90L).items();

        assertThat(rewards).hasSize(13);
        assertThat(rewards).extracting(reward -> reward.itemId()).doesNotHaveDuplicates()
                .doesNotContain(unknown.getItem().getId(), attendanceOnly.getItem().getId());
        assertThat(rewards).extracting(reward -> reward.assetKey()).contains(
                ASSET_PREFIX + "furniture/stocking-fireplace-animated-v1.webp",
                ASSET_PREFIX + "furniture/christmas-tree-animated-v1.webp", "items/regular/sofa.png");
        assertThat(rewards.stream().mapToDouble(reward -> reward.probabilityPercent()).sum())
                .isCloseTo(100.0, org.assertj.core.api.Assertions.within(0.0000001));
    }

    @Test
    void 메타_준비판정은_OFF_잘못된머신_기간밖_빈등급을_false로_반환한다() {
        GachaService enabled = service(true, DURING_EVENT, () -> 0L);
        assertThat(enabled.isChristmasReady(null, DURING_EVENT)).isFalse();
        assertThat(enabled.isChristmasReady(furniture, DURING_EVENT)).isFalse();
        assertThat(service(false, DURING_EVENT, () -> 0L).isChristmasReady(christmas, DURING_EVENT)).isFalse();
        assertThat(enabled.isChristmasReady(christmas, Instant.parse("2026-12-25T15:00:00Z"))).isFalse();
        regularEntries.removeIf(entry -> GachaRarity.LEGENDARY.equals(entry.getRarity()));
        stubPools();
        assertThat(enabled.isChristmasReady(christmas, DURING_EVENT)).isFalse();
        assertNoPaymentOrGrant();
    }

    @Test
    void 메타_준비판정은_두그룹과_모든등급이_유효하면_true이며_보상을_지급하지_않는다() {
        assertThat(service(true, DURING_EVENT, () -> 0L).isChristmasReady(christmas, DURING_EVENT)).isTrue();
        assertNoPaymentOrGrant();
    }

    @Test
    void 크리스마스테마로_잘못_생성된_옛머신은_이벤트를_우회할_수_없다() {
        Gacha legacy = machine(7L, "snowy_christmas_202611", christmasTheme);
        when(machines.findAllWithTheme()).thenReturn(List.of(legacy));
        when(machines.findById(7L)).thenReturn(Optional.of(legacy));
        GachaService service = service(true, DURING_EVENT, () -> 0L);
        assertThat(service.getGachaList().items()).isEmpty();
        assertThat(service.getGacha(7L).active()).isFalse();
        assertInactive(() -> service.getRewards(1L, 7L));
        assertInactive(() -> service.draw(1L, 7L, new GachaDrawRequest(1)));
        assertNoPaymentOrGrant();
    }

    @Test
    void 잠금조회에서_탈퇴한_회원이면_지갑과_풀을_조회하거나_지급하지_않는다() {
        user.softDelete(DURING_EVENT);
        assertThatThrownBy(() -> service(true, DURING_EVENT, () -> 0L)
                .draw(1L, 90L, new GachaDrawRequest(1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(MemberErrorCode.USER_NOT_FOUND));
        verify(wallets, never()).findWithLockByUserIdAndCurrencyType(1L, CurrencyType.COIN);
        verify(machines, never()).findById(90L);
        verify(pools, never()).findByGachaIdAndActiveIsTrue(90L);
        assertNoPaymentOrGrant();
    }

    private void assertInactive(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(GachaErrorCode.GACHA_INACTIVE));
    }

    private void assertNoPaymentOrGrant() {
        assertThat(coin.getBalance()).isEqualTo(1_000);
        assertThat(diamond.getBalance()).isZero();
        verify(inventory, never()).save(any());
        verify(characters, never()).save(any());
        verify(history, never()).record(any(), anyInt(), any(), any(), any());
    }

    private GachaService service(boolean enabled, Instant now, RandomGenerator random) {
        return service(enabled, Clock.fixed(now, ZoneOffset.UTC), random);
    }

    private GachaService service(boolean enabled, Clock clock, RandomGenerator random) {
        return new GachaService(machines, pools, inventory, characters, wallets, users, history, unlock,
                new GachaDrawPolicy(random), new ChristmasGachaDrawPolicy(random),
                new ChristmasEventPolicy(enabled), clock);
    }

    private void stubPools() {
        when(pools.findByGachaIdAndActiveIsTrue(90L)).thenReturn(christmasEntries);
        when(pools.findActiveRewardsByGachaId(90L)).thenReturn(christmasEntries);
        when(pools.findByGachaIdAndActiveIsTrue(3L)).thenReturn(regularEntries);
        when(pools.findActiveRewardsByGachaId(3L)).thenReturn(regularEntries);
    }

    private Gacha machine(long id, String code, Theme theme) {
        Gacha machine = new Gacha(code, code, CurrencyType.COIN, 25, 1, theme, true);
        ReflectionTestUtils.setField(machine, "id", id);
        return machine;
    }

    private GachaPoolEntry entry(Gacha machine, Theme theme, String asset, String rarity) {
        String category = asset.contains("/wallpaper/") ? "wallpaper" : asset.contains("/floor/") ? "floor" : "furniture";
        boolean surface = !category.equals("furniture");
        Item item = new Item(theme, category, surface ? "surface_slot" : "positioned",
                surface ? category : null, null, asset, null, null, asset, true, true);
        ReflectionTestUtils.setField(item, "id", nextItemId++);
        return GachaPoolEntry.itemEntry(machine, item, rarity);
    }

    private List<GachaPoolEntry> christmasEntries() {
        return new ArrayList<>(List.of(
                entry(christmas, christmasTheme, ASSET_PREFIX + "furniture/snowy-window-animated-v1.webp", GachaRarity.NORMAL),
                entry(christmas, christmasTheme, ASSET_PREFIX + "furniture/gift-stack-v1.png", GachaRarity.NORMAL),
                entry(christmas, christmasTheme, ASSET_PREFIX + "furniture/winter-rug-v1.png", GachaRarity.NORMAL),
                entry(christmas, christmasTheme, ASSET_PREFIX + "wallpaper/snowflake-wallpaper-panel-free-v2.png", GachaRarity.NORMAL),
                entry(christmas, christmasTheme, ASSET_PREFIX + "floor/honey-winter-floor-v1.png", GachaRarity.NORMAL),
                entry(christmas, christmasTheme, ASSET_PREFIX + "furniture/plaid-sofa-v1.png", GachaRarity.RARE),
                entry(christmas, christmasTheme, ASSET_PREFIX + "furniture/cocoa-table-animated-v1.webp", GachaRarity.RARE),
                entry(christmas, christmasTheme, ASSET_PREFIX + "furniture/christmas-wreath-v1.png", GachaRarity.RARE),
                entry(christmas, christmasTheme, ASSET_PREFIX + "furniture/stocking-fireplace-animated-v1.webp", GachaRarity.LEGENDARY),
                entry(christmas, christmasTheme, ASSET_PREFIX + "furniture/christmas-tree-animated-v1.webp", GachaRarity.LEGENDARY)));
    }
}
