package com.triples.rougether.userapi.gacha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.gacha.entity.Gacha;
import com.triples.rougether.domain.gacha.entity.GachaPoolEntry;
import com.triples.rougether.domain.gacha.entity.RewardType;
import com.triples.rougether.domain.gacha.repository.GachaPoolEntryRepository;
import com.triples.rougether.domain.gacha.repository.GachaRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.gacha.dto.GachaDrawRequest;
import com.triples.rougether.userapi.gacha.dto.GachaDrawResponse;
import com.triples.rougether.userapi.gacha.dto.GachaRewardListResponse;
import com.triples.rougether.userapi.gacha.error.GachaErrorCode;
import com.triples.rougether.userapi.gacha.service.GachaService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class GachaServiceTest {

    @Mock private GachaRepository gachaRepository;
    @Mock private GachaPoolEntryRepository poolRepository;
    @Mock private UserItemRepository userItemRepository;
    @Mock private UserWalletRepository walletRepository;
    @Mock private UserCharacterRepository userCharacterRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private GachaService gachaService;

    @BeforeEach
    void stubUserLock() {
        // draw 는 획득 경로 직렬화를 위해 user 행 락을 먼저 잡는다 — draw 를 안 타는 테스트도 있어 lenient
        org.mockito.Mockito.lenient()
                .when(userRepository.findByIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Optional.of(mock(com.triples.rougether.domain.member.entity.User.class)));
    }

    private Gacha availableGacha() {
        Gacha g = mock(Gacha.class);
        when(g.isAvailableAt(any(Instant.class))).thenReturn(true);
        return g;
    }

    private Gacha activeGacha(int cost) {
        Gacha g = availableGacha();
        when(g.getCostAmount()).thenReturn(cost);
        return g;
    }

    @Test
    void 뽑기_목록은_활성이고_운영_기간_안인_머신만_조회한다() {
        Instant now = Instant.now();
        Gacha available = gacha("available", true, null, null);
        Gacha notStarted = gacha("not_started", true, now.plusSeconds(60), null);
        Gacha expired = gacha("expired", true, null, now.minusSeconds(60));
        Gacha inactive = gacha("inactive", false, null, null);
        when(gachaRepository.findAll()).thenReturn(List.of(available, notStarted, expired, inactive));

        assertThat(gachaService.getGachaList().items())
                .extracting(response -> response.name())
                .containsExactly("available");
    }

    // pool 을 1개 아이템(rarity 일반)으로 만들어 추첨 결과를 결정적으로 고정.
    private Item singleItemPool(Long itemId, Long gachaId) {
        Item item = mock(Item.class);
        when(item.getId()).thenReturn(itemId);
        when(item.getName()).thenReturn("가구A");
        when(item.getAssetKey()).thenReturn("items/a.png");
        GachaPoolEntry entry = mock(GachaPoolEntry.class);
        when(entry.getRewardType()).thenReturn(RewardType.ITEM);
        when(entry.getItem()).thenReturn(item);
        when(entry.getRarity()).thenReturn("일반");
        when(poolRepository.findByGachaIdAndActiveIsTrue(gachaId)).thenReturn(List.of(entry));
        return item;
    }

    private Gacha gacha(String name, boolean active, Instant startsAt, Instant endsAt) {
        Gacha gacha = new Gacha(name, name, CurrencyType.COIN, 25, 1, null, active);
        ReflectionTestUtils.setField(gacha, "startsAt", startsAt);
        ReflectionTestUtils.setField(gacha, "endsAt", endsAt);
        return gacha;
    }

    @Test
    void 뽑기횟수가_1이나_6이_아니면_거부한다() {
        assertThatThrownBy(() -> gachaService.draw(1L, 10L, new GachaDrawRequest(2)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(GachaErrorCode.INVALID_DRAW_COUNT));
    }

    @ParameterizedTest
    @ValueSource(ints = {6, 10})
    void 신규_6과_구버전_10요청은_다섯회_비용으로_여섯개를_뽑는다(int requestCount) {
        Gacha g = activeGacha(25);
        when(gachaRepository.findById(10L)).thenReturn(Optional.of(g));
        UserWallet wallet = mock(UserWallet.class);
        when(wallet.getBalance()).thenReturn(1000);
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.COIN))
                .thenReturn(Optional.of(wallet));
        UserWallet diaWallet = mock(UserWallet.class);
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.DIAMOND))
                .thenReturn(Optional.of(diaWallet));
        singleItemPool(100L, 10L);
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(1L)).thenReturn(List.of());
        when(userCharacterRepository.findByUserId(1L)).thenReturn(List.of());
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));

        GachaDrawResponse res = gachaService.draw(1L, 10L, new GachaDrawRequest(requestCount));

        verify(wallet).spend(125);
        verify(userItemRepository, times(1)).save(any(UserItem.class));
        verify(diaWallet).add(15);
        assertThat(res.results()).hasSize(6);
        assertThat(res.results()).extracting(result -> result.rewardType())
                .containsExactly("ITEM", "CURRENCY", "CURRENCY", "CURRENCY", "CURRENCY", "CURRENCY");
    }

    @Test
    void 비활성_뽑기는_거부한다() {
        Gacha g = mock(Gacha.class);
        when(g.isAvailableAt(any(Instant.class))).thenReturn(false);
        when(gachaRepository.findById(10L)).thenReturn(Optional.of(g));

        assertThatThrownBy(() -> gachaService.draw(1L, 10L, new GachaDrawRequest(1)))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(GachaErrorCode.GACHA_INACTIVE));
    }

    @Test
    void 활성_풀의_아이템과_캐릭터를_보유여부와_함께_조회한다() {
        Gacha gacha = availableGacha();
        when(gachaRepository.findById(10L)).thenReturn(Optional.of(gacha));

        Item item = mock(Item.class);
        when(item.getId()).thenReturn(100L);
        when(item.getName()).thenReturn("한옥 좌식상");
        when(item.getAssetKey()).thenReturn("items/calm-hanok/furniture/low-table.png");
        GachaPoolEntry itemEntry = mock(GachaPoolEntry.class);
        when(itemEntry.getRewardType()).thenReturn(RewardType.ITEM);
        when(itemEntry.getItem()).thenReturn(item);
        when(itemEntry.getRarity()).thenReturn("희귀");

        Character character = mock(Character.class);
        when(character.getId()).thenReturn(5L);
        when(character.getName()).thenReturn("고양이");
        when(character.getBaseAssetKey()).thenReturn("characters/cat/thumbnail.png");
        GachaPoolEntry characterEntry = mock(GachaPoolEntry.class);
        when(characterEntry.getRewardType()).thenReturn(RewardType.CHARACTER);
        when(characterEntry.getCharacter()).thenReturn(character);

        when(poolRepository.findActiveRewardsByGachaId(10L))
                .thenReturn(List.of(itemEntry, characterEntry));

        when(userItemRepository.findOwnedItemIdsByUserId(1L)).thenReturn(List.of(100L));
        when(userCharacterRepository.findOwnedCharacterIdsByUserId(1L)).thenReturn(List.of());

        GachaRewardListResponse response = gachaService.getRewards(1L, 10L);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).rewardType()).isEqualTo("ITEM");
        assertThat(response.items().get(0).itemId()).isEqualTo(100L);
        assertThat(response.items().get(0).characterId()).isNull();
        assertThat(response.items().get(0).rarity()).isEqualTo("희귀");
        assertThat(response.items().get(0).owned()).isTrue();
        assertThat(response.items().get(1).rewardType()).isEqualTo("CHARACTER");
        assertThat(response.items().get(1).itemId()).isNull();
        assertThat(response.items().get(1).characterId()).isEqualTo(5L);
        assertThat(response.items().get(1).owned()).isFalse();
    }

    @Test
    void 보상_목록은_참조가_깨진_풀_엔트리를_제외한다() {
        Gacha gacha = availableGacha();
        when(gachaRepository.findById(10L)).thenReturn(Optional.of(gacha));
        GachaPoolEntry brokenItemEntry = mock(GachaPoolEntry.class);
        when(brokenItemEntry.getRewardType()).thenReturn(RewardType.ITEM);
        when(poolRepository.findActiveRewardsByGachaId(10L)).thenReturn(List.of(brokenItemEntry));
        when(userItemRepository.findOwnedItemIdsByUserId(1L)).thenReturn(List.of());
        when(userCharacterRepository.findOwnedCharacterIdsByUserId(1L)).thenReturn(List.of());

        GachaRewardListResponse response = gachaService.getRewards(1L, 10L);

        assertThat(response.items()).isEmpty();
        verify(userItemRepository, never()).findByUserIdAndDeletedAtIsNull(1L);
        verify(userCharacterRepository, never()).findByUserIdAndDeletedAtIsNull(1L);
    }

    @Test
    void 없는_뽑기의_보상_목록은_조회할_수_없다() {
        when(gachaRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gachaService.getRewards(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(GachaErrorCode.GACHA_NOT_FOUND));

        verify(poolRepository, never()).findActiveRewardsByGachaId(10L);
    }

    @Test
    void 비활성이나_운영_기간_밖인_뽑기의_보상_목록은_조회할_수_없다() {
        Gacha gacha = mock(Gacha.class);
        when(gacha.isAvailableAt(any(Instant.class))).thenReturn(false);
        when(gachaRepository.findById(10L)).thenReturn(Optional.of(gacha));

        assertThatThrownBy(() -> gachaService.getRewards(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(GachaErrorCode.GACHA_INACTIVE));

        verify(poolRepository, never()).findActiveRewardsByGachaId(anyLong());
        verify(userItemRepository, never()).findOwnedItemIdsByUserId(anyLong());
        verify(userCharacterRepository, never()).findOwnedCharacterIdsByUserId(anyLong());
    }

    @Test
    void 코인이_부족하면_거부하고_차감하지_않는다() {
        Gacha g = activeGacha(25);
        when(gachaRepository.findById(10L)).thenReturn(Optional.of(g));
        UserWallet wallet = mock(UserWallet.class);
        when(wallet.getBalance()).thenReturn(10);
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.COIN)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> gachaService.draw(1L, 10L, new GachaDrawRequest(1)))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(GachaErrorCode.INSUFFICIENT_COIN));
        verify(wallet, never()).spend(anyInt());
    }

    @Test
    void 미소유_아이템은_지급되고_코인이_차감된다() {
        Gacha g = activeGacha(25);
        when(gachaRepository.findById(10L)).thenReturn(Optional.of(g));
        UserWallet wallet = mock(UserWallet.class);
        when(wallet.getBalance()).thenReturn(1000);
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.COIN)).thenReturn(Optional.of(wallet));
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.DIAMOND)).thenReturn(Optional.empty());
        singleItemPool(100L, 10L);
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(1L)).thenReturn(List.of());
        when(userCharacterRepository.findByUserId(1L)).thenReturn(List.of());
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));

        GachaDrawResponse res = gachaService.draw(1L, 10L, new GachaDrawRequest(1));

        verify(wallet).spend(25);
        verify(userItemRepository).save(any(UserItem.class));
        verify(wallet).add(0);
        assertThat(res.results()).hasSize(1);
        assertThat(res.results().get(0).converted()).isFalse();
        assertThat(res.results().get(0).rewardType()).isEqualTo("ITEM");
        // 응답에 두 재화 잔액이 모두 담긴다 (다이아 지갑이 없으면 0)
        assertThat(res.wallets()).extracting(w -> w.currencyType())
                .containsExactly(CurrencyType.COIN, CurrencyType.DIAMOND);
        assertThat(res.wallets().get(1).balance()).isZero();
    }

    @Test
    void 이미_소유한_아이템은_지급대신_다이아3으로_전환된다() {
        Gacha g = activeGacha(25);
        when(gachaRepository.findById(10L)).thenReturn(Optional.of(g));
        UserWallet wallet = mock(UserWallet.class);
        when(wallet.getBalance()).thenReturn(1000);
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.COIN)).thenReturn(Optional.of(wallet));
        UserWallet diaWallet = mock(UserWallet.class);
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.DIAMOND)).thenReturn(Optional.of(diaWallet));
        Item item = singleItemPool(100L, 10L);
        UserItem owned = mock(UserItem.class);
        when(owned.getItem()).thenReturn(item);
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(1L)).thenReturn(List.of(owned));
        when(userCharacterRepository.findByUserId(1L)).thenReturn(List.of());
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));

        GachaDrawResponse res = gachaService.draw(1L, 10L, new GachaDrawRequest(1));

        verify(wallet).spend(25);
        verify(wallet).add(0);
        verify(diaWallet).add(3);
        verify(userItemRepository, never()).save(any());
        assertThat(res.results().get(0).converted()).isTrue();
        assertThat(res.results().get(0).refundCurrencyType()).isEqualTo(CurrencyType.DIAMOND);
        assertThat(res.results().get(0).refundAmount()).isEqualTo(3);
    }

    @Test
    void 아이템_중복_전환시_다이아_지갑이_없으면_새로_발급한다() {
        Gacha g = activeGacha(25);
        when(gachaRepository.findById(10L)).thenReturn(Optional.of(g));
        UserWallet wallet = mock(UserWallet.class);
        when(wallet.getBalance()).thenReturn(1000);
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.COIN)).thenReturn(Optional.of(wallet));
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.DIAMOND)).thenReturn(Optional.empty());
        UserWallet createdDia = mock(UserWallet.class);
        when(createdDia.getBalance()).thenReturn(3);
        when(walletRepository.save(any(UserWallet.class))).thenReturn(createdDia);
        Item item = singleItemPool(100L, 10L);
        UserItem owned = mock(UserItem.class);
        when(owned.getItem()).thenReturn(item);
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(1L)).thenReturn(List.of(owned));
        when(userCharacterRepository.findByUserId(1L)).thenReturn(List.of());
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));

        GachaDrawResponse res = gachaService.draw(1L, 10L, new GachaDrawRequest(1));

        verify(walletRepository).save(any(UserWallet.class));
        verify(createdDia).add(3);
        assertThat(res.wallets().get(1).balance()).isEqualTo(3);
    }

    // 캐릭터 pool 을 1개(rarity 미부여)로 만들어 추첨 결과를 결정적으로 고정.
    private Character singleCharacterPool(Long characterId, Long gachaId) {
        Character ch = mock(Character.class);
        when(ch.getId()).thenReturn(characterId);
        when(ch.getName()).thenReturn("곰");
        when(ch.getBaseAssetKey()).thenReturn("characters/bear.png");
        GachaPoolEntry entry = mock(GachaPoolEntry.class);
        when(entry.getRewardType()).thenReturn(RewardType.CHARACTER);
        when(entry.getCharacter()).thenReturn(ch);
        when(poolRepository.findByGachaIdAndActiveIsTrue(gachaId)).thenReturn(List.of(entry));
        return ch;
    }

    @Test
    void 미소유_캐릭터는_지급되고_코인1000이_차감된다() {
        Gacha g = activeGacha(500);
        when(gachaRepository.findById(10L)).thenReturn(Optional.of(g));
        UserWallet wallet = mock(UserWallet.class);
        when(wallet.getBalance()).thenReturn(2000);
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.COIN)).thenReturn(Optional.of(wallet));
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.DIAMOND)).thenReturn(Optional.empty());
        singleCharacterPool(5L, 10L);
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(1L)).thenReturn(List.of());
        when(userCharacterRepository.findByUserId(1L)).thenReturn(List.of());
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));

        GachaDrawResponse res = gachaService.draw(1L, 10L, new GachaDrawRequest(1));

        verify(wallet).spend(500);
        verify(userCharacterRepository).save(any(UserCharacter.class));
        assertThat(res.results().get(0).rewardType()).isEqualTo("CHARACTER");
        assertThat(res.results().get(0).characterId()).isEqualTo(5L);
        assertThat(res.results().get(0).converted()).isFalse();
    }

    @Test
    void 이미_소유한_캐릭터는_지급대신_코인100_환급된다() {
        Gacha g = activeGacha(500);
        when(gachaRepository.findById(10L)).thenReturn(Optional.of(g));
        UserWallet wallet = mock(UserWallet.class);
        when(wallet.getBalance()).thenReturn(2000);
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.COIN)).thenReturn(Optional.of(wallet));
        when(walletRepository.findWithLockByUserIdAndCurrencyType(1L, CurrencyType.DIAMOND)).thenReturn(Optional.empty());
        Character ch = singleCharacterPool(5L, 10L);
        UserCharacter owned = mock(UserCharacter.class);
        when(owned.getCharacter()).thenReturn(ch);
        when(userItemRepository.findByUserIdAndDeletedAtIsNull(1L)).thenReturn(List.of());
        when(userCharacterRepository.findByUserId(1L)).thenReturn(List.of(owned));
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));

        GachaDrawResponse res = gachaService.draw(1L, 10L, new GachaDrawRequest(1));

        verify(wallet).spend(500);
        verify(wallet).add(100);
        verify(userCharacterRepository, never()).save(any());
        assertThat(res.results().get(0).converted()).isTrue();
        assertThat(res.results().get(0).refundCurrencyType()).isEqualTo(CurrencyType.COIN);
        assertThat(res.results().get(0).refundAmount()).isEqualTo(100);
    }
}
